"""Valida el compose de la raiz sin Docker: que el YAML parsee, que las anclas
se mezclen y que las referencias entre servicios existan y sean coherentes.

No reemplaza a `docker compose config` ni a un `up` real: no verifica que las
imagenes existan, ni que los contextos de build compilen, ni que los brokers
formen cluster. Comprueba lo que el archivo por si solo puede probar.

    python infra/validar-compose.py [docker-compose.yml]
"""
import os
import re
import sys

import yaml

RUTA = sys.argv[1] if len(sys.argv) > 1 else "docker-compose.yml"
BASE = os.path.dirname(os.path.abspath(RUTA))

with open(RUTA, encoding="utf-8") as f:
    doc = yaml.safe_load(f)

servicios = doc["services"]
errores = []


def env(nombre):
    return servicios[nombre].get("environment") or {}


def depende(nombre, dep, condicion):
    cond = (servicios[nombre].get("depends_on") or {}).get(dep, {})
    return cond.get("condition") == condicion


microservicios = [s for s in servicios if s.startswith("ms-")]
con_kafka = [s for s in microservicios if s not in ("ms-notificaciones", "ms-rabbit-admin")]
con_rabbit = ["ms-carrito", "ms-notificaciones", "ms-rabbit-admin"]
con_mysql = ["ms-pago", "ms-productos", "ms-carrito", "ms-notificaciones"]
frentes = {"consola": None, "tienda": None}

print(f"servicios ({len(servicios)}): {', '.join(servicios)}")
print(f"redes: {list(doc.get('networks', {}))}  |  volumenes: {len(doc.get('volumes', {}))}")
print()

# --- las anclas se mezclaron ---
for s in ("kafka1", "kafka2", "kafka3"):
    e = env(s)
    if e.get("KAFKA_MIN_INSYNC_REPLICAS") != 2:
        errores.append(f"{s}: no heredo KAFKA_MIN_INSYNC_REPLICAS de la ancla")
    if not servicios[s].get("healthcheck"):
        errores.append(f"{s}: sin healthcheck heredado")
    if f"{s}:29092" not in e["KAFKA_ADVERTISED_LISTENERS"]:
        errores.append(f"{s}: el listener INTERNO no se anuncia con su propio nombre")
for s in microservicios:
    if "CORS_ORIGENES" not in env(s) or servicios[s].get("mem_limit") is None:
        errores.append(f"{s}: no heredo las anclas spring-env / ms-common")

# --- todo depends_on apunta a un servicio que existe ---
for nombre, cfg in servicios.items():
    for dep in (cfg.get("depends_on") or {}):
        if dep not in servicios:
            errores.append(f"{nombre}: depende de '{dep}', que no existe")

# --- todo servicio esta en la red y todo volumen usado esta declarado ---
declarados = set(doc.get("volumes") or {})
for nombre, cfg in servicios.items():
    if "pedidos360" not in (cfg.get("networks") or []):
        errores.append(f"{nombre}: no esta en la red pedidos360")
    for v in (cfg.get("volumes") or []):
        origen = v.split(":")[0]
        if origen.startswith((".", "/")):
            if not os.path.exists(os.path.join(BASE, origen)):
                errores.append(f"{nombre}: monta '{origen}', que no existe en el repo")
        elif origen not in declarados:
            errores.append(f"{nombre}: usa el volumen '{origen}' sin declararlo")

# --- puertos publicados unicos (un choque hace fallar el `up` entero) ---
vistos = {}
for nombre, cfg in servicios.items():
    for p in (cfg.get("ports") or []):
        host = str(p).split(":")[0]
        if host in vistos:
            errores.append(f"puerto {host} publicado por {vistos[host]} y por {nombre}")
        vistos[host] = nombre

# --- Kafka: listener INTERNO y topicos creados antes de arrancar ---
# El 9092 del host no existe dentro de la red de Docker: usarlo aca es el error
# clasico que deja los servicios sin poder producir.
for nombre in con_kafka:
    bootstrap = env(nombre)["SPRING_KAFKA_BOOTSTRAP_SERVERS"]
    if "29092" not in bootstrap or "localhost" in bootstrap:
        errores.append(f"{nombre}: bootstrap '{bootstrap}' deberia usar kafkaN:29092")
    if not depende(nombre, "crear-topicos", "service_completed_successfully"):
        errores.append(f"{nombre}: deberia esperar a que crear-topicos termine bien")

ct = servicios["crear-topicos"]
if "/scripts/crear-topicos.sh" not in " ".join(ct.get("command") or []):
    errores.append("crear-topicos: no ejecuta infra/kafka/crear-topicos.sh")
script = os.path.join(BASE, "infra", "kafka", "crear-topicos.sh")
if os.path.exists(script):
    with open(script, "rb") as f:
        crudo = f.read()
    if b"\r\n" in crudo:
        errores.append("crear-topicos.sh tiene CRLF: bash falla dentro del contenedor")
    texto = crudo.decode("utf-8")
    for requisito in ("--if-not-exists", "--replication-factor", "min.insync.replicas=",
                      "MIN_ISR=2", "cleanup.policy=compact", "compression.type=lz4"):
        if requisito not in texto:
            errores.append(f"crear-topicos.sh: falta '{requisito}'")
    if ".DLT" not in texto:
        errores.append("crear-topicos.sh: no crea los DLT")
    for topico in ("interacciones-user", "payment-attempts", "pagos-resultado", "catalogo-productos"):
        if topico not in texto:
            errores.append(f"crear-topicos.sh: no crea {topico}")
else:
    errores.append("falta infra/kafka/crear-topicos.sh")

# --- RabbitMQ: tres nodos que de verdad pueden formar cluster ---
nodos = ["rabbitmq1", "rabbitmq2", "rabbitmq3"]
cookies = {env(n).get("RABBITMQ_ERLANG_COOKIE") for n in nodos}
if len(cookies) != 1:
    errores.append("rabbitmq: la cookie Erlang no es identica en los 3 nodos (no forman cluster)")
for n in nodos:
    if servicios[n].get("hostname") != n:
        errores.append(f"{n}: hostname debe ser '{n}' (classic_config lo busca como rabbit@{n})")
    if not any("rabbitmq.conf" in v for v in servicios[n].get("volumes") or []):
        errores.append(f"{n}: no monta rabbitmq.conf")
for nombre in con_rabbit:
    direcciones = env(nombre).get("RABBITMQ_ADDRESSES", "")
    if "localhost" in direcciones or not all(n in direcciones for n in nodos):
        errores.append(f"{nombre}: RABBITMQ_ADDRESSES deberia listar rabbitmq1..3 por nombre interno")
    for n in nodos:
        if not depende(nombre, n, "service_healthy"):
            errores.append(f"{nombre}: deberia esperar a que {n} este sano")

# --- MySQL: cada servicio con SU base, por nombre interno ---
bases = {}
for nombre in con_mysql:
    url = env(nombre).get("SPRING_DATASOURCE_URL", "")
    m = re.search(r"//([^:/]+):\d+/(\w+)", url)
    if not m or m.group(1) != "mysql-db":
        errores.append(f"{nombre}: SPRING_DATASOURCE_URL deberia apuntar a mysql-db")
        continue
    if m.group(2) in bases:
        errores.append(f"{nombre} y {bases[m.group(2)]} comparten la base {m.group(2)}")
    bases[m.group(2)] = nombre
    if not depende(nombre, "mysql-db", "service_healthy"):
        errores.append(f"{nombre}: deberia esperar a que mysql-db este sano")

# --- servidor a servidor: nombres de Docker, nunca localhost ---
if "localhost" in env("ms-carrito").get("PRODUCTOS_URL", "localhost"):
    errores.append("ms-carrito: PRODUCTOS_URL es servidor a servidor, debe usar ms-productos:8086")
if "localhost" in env("ms-recomendaciones").get("DYNAMODB_ENDPOINT", ""):
    errores.append("ms-recomendaciones: DYNAMODB_ENDPOINT debe usar dynamodb-local, no localhost")

# --- lo que abre el navegador va con localhost y el puerto publicado ---
for frente in frentes:
    frentes[frente] = servicios[frente]["ports"][0].split(":")[0]
    for clave, valor in env(frente).items():
        if clave.startswith("API_") and "localhost" not in valor:
            errores.append(f"{frente}: {clave}={valor} lo consume el navegador, debe ser localhost")

pago = env("ms-pago")
if "localhost" not in pago["PAGOS_RETURN_URL"]:
    errores.append("ms-pago: PAGOS_RETURN_URL no es alcanzable desde el navegador")
if pago["FRONT_URL"] != f"http://localhost:{frentes['consola']}":
    errores.append("ms-pago: FRONT_URL no apunta al puerto publicado de la consola")
if pago["FRONT_URL_TIENDA"] != f"http://localhost:{frentes['tienda']}":
    errores.append("ms-pago: FRONT_URL_TIENDA no apunta al puerto publicado de la tienda")

# --- el CORS de cada servicio incluye los puertos publicados de ambos frentes ---
for nombre in microservicios:
    cors = env(nombre)["CORS_ORIGENES"]
    for frente, puerto in frentes.items():
        if f"localhost:{puerto}" not in cors:
            errores.append(f"{nombre}: CORS_ORIGENES no incluye http://localhost:{puerto} ({frente})")

print()
if errores:
    print(f"{len(errores)} problema(s):")
    for e in errores:
        print("  -", e)
    sys.exit(1)

print("compose coherente: anclas, dependencias, red, volumenes y montajes, puertos")
print("unicos, bootstrap interno, script de topicos, cluster RabbitMQ, una base por")
print("servicio, URL internas vs. de navegador y origenes CORS de ambos frentes.")
