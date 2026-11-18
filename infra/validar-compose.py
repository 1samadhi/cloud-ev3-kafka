"""Valida el compose de la raiz sin Docker: que el YAML parsee, que las anclas
se mezclen y que las referencias entre servicios existan y sean coherentes.

No reemplaza a `docker compose config` ni a un `up` real: no verifica que las
imagenes existan, ni que los contextos de build compilen.
"""
import sys
import yaml

RUTA = sys.argv[1] if len(sys.argv) > 1 else "docker-compose.yml"

with open(RUTA, encoding="utf-8") as f:
    doc = yaml.safe_load(f)

servicios = doc["services"]
errores = []

print(f"servicios ({len(servicios)}): {', '.join(servicios)}")
print(f"redes: {list(doc.get('networks', {}))}  |  volumenes: {len(doc.get('volumes', {}))}")
print()

# --- las anclas se mezclaron en cada broker ---
for s in ("kafka1", "kafka2", "kafka3"):
    env = servicios[s]["environment"]
    if env.get("KAFKA_MIN_INSYNC_REPLICAS") != 2:
        errores.append(f"{s}: no heredo KAFKA_MIN_INSYNC_REPLICAS de la ancla")
    if not servicios[s].get("healthcheck"):
        errores.append(f"{s}: sin healthcheck heredado")
    if f"{s}:29092" not in env["KAFKA_ADVERTISED_LISTENERS"]:
        errores.append(f"{s}: el listener INTERNO no se anuncia con su propio nombre")
print("anclas de kafka: ok" if not errores else "anclas: FALLA")

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
        if not origen.startswith((".", "/")) and origen not in declarados:
            errores.append(f"{nombre}: usa el volumen '{origen}' sin declararlo")

# --- los microservicios hablan con Kafka por el listener INTERNO ---
# El 9092 del host no existe dentro de la red de Docker: usarlo aca es el error
# clasico que deja los servicios sin poder producir.
for nombre in ("ms-pago", "ms-recomendaciones", "ms-kafka-admin"):
    bootstrap = servicios[nombre]["environment"]["SPRING_KAFKA_BOOTSTRAP_SERVERS"]
    if "29092" not in bootstrap or "localhost" in bootstrap:
        errores.append(f"{nombre}: bootstrap '{bootstrap}' deberia usar kafkaN:29092")

# --- lo que abre el navegador va con localhost, no con el nombre del servicio ---
# Son las dos URL que el usuario recorre a mano: si llevan un nombre de Docker,
# el navegador no las resuelve.
pago = servicios["ms-pago"]["environment"]
for clave in ("PAGOS_RETURN_URL", "FRONT_URL"):
    if "localhost" not in pago[clave]:
        errores.append(f"ms-pago: {clave}={pago[clave]} no es alcanzable desde el navegador")

consola = servicios["consola"]["environment"]
for clave in ("API_PAGOS", "API_RECOMENDACIONES", "API_ADMIN"):
    if "localhost" not in consola[clave]:
        errores.append(f"consola: {clave}={consola[clave]} lo consume el navegador, debe ser localhost")

# --- el origen declarado en CORS coincide con el puerto publicado del front ---
publicado = servicios["consola"]["ports"][0].split(":")[0]
for nombre in ("ms-pago", "ms-recomendaciones", "ms-kafka-admin"):
    cors = servicios[nombre]["environment"]["CORS_ORIGENES"]
    if f"localhost:{publicado}" not in cors:
        errores.append(
            f"{nombre}: CORS_ORIGENES no incluye http://localhost:{publicado}, "
            "el puerto en el que se publica la consola"
        )
if pago["FRONT_URL"] != f"http://localhost:{publicado}":
    errores.append("ms-pago: FRONT_URL no apunta al puerto publicado de la consola")

# --- los topicos se crean antes de que arranque cualquier servicio ---
for nombre in ("ms-pago", "ms-recomendaciones", "ms-kafka-admin"):
    cond = (servicios[nombre].get("depends_on") or {}).get("crear-topicos", {})
    if cond.get("condition") != "service_completed_successfully":
        errores.append(f"{nombre}: deberia esperar a que crear-topicos termine bien")

cmd = " ".join(servicios["crear-topicos"]["command"].split())
for requisito in ("--if-not-exists", "--partitions 3", "--replication-factor 3", "min.insync.replicas=2"):
    if requisito not in cmd:
        errores.append(f"crear-topicos: falta '{requisito}' en el comando")

print()
if errores:
    print(f"{len(errores)} problema(s):")
    for e in errores:
        print("  -", e)
    sys.exit(1)

print("compose coherente: dependencias, red, volumenes, bootstrap interno,")
print("URL de navegador, origenes CORS y creacion de topicos.")
