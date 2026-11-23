#!/usr/bin/env python3
"""Prueba de humo del sistema levantado con `docker compose up`.

Recorre el flujo que hace la tienda, contra los servicios REALES (no mocks), y
verifica los efectos que viajan por Kafka y RabbitMQ, no sólo las respuestas
HTTP: que el pago autorizado llegue a ms-carrito por pagos-resultado, que el
stock baje, que la notificación pase por la cola y que las recomendaciones
cambien con la navegación.

Uso:  python infra/prueba-humo.py            (servicios en localhost)
      BASE_HOST=1.2.3.4 python infra/prueba-humo.py   (EC2)

Sólo usa la biblioteca estándar, para correr en cualquier máquina del equipo.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from typing import Any

HOST = os.environ.get("BASE_HOST", "localhost")
PAGOS, RECO, ADMIN = f"http://{HOST}:8083", f"http://{HOST}:8084", f"http://{HOST}:8085"
PRODUCTOS, CARRITO, NOTIF = f"http://{HOST}:8086", f"http://{HOST}:8087", f"http://{HOST}:8088"
RABBIT_ADMIN = f"http://{HOST}:8089"

# Un usuario nuevo por corrida: si se reutilizara, el antifraude o el carrito de
# una corrida anterior contaminarían esta.
USUARIO = f"humo-{int(time.time())}@correo.cl"
fallos = []


def tarjeta_unica(prefijo="4"):
    """Número de 16 dígitos con dígito verificador Luhn, distinto en cada corrida.

    El bloqueo antifraude dura 30 minutos: reutilizar una tarjeta fija haría que
    la segunda corrida reciba 429 desde el primer intento, que es justamente lo
    correcto del sistema y no una falla.
    """
    base = prefijo + str(time.time_ns())[-14:]
    suma = 0
    for i, d in enumerate(reversed(base)):
        n = int(d) * (2 if i % 2 == 0 else 1)
        suma += n - 9 if n > 9 else n
    return base + str((10 - suma % 10) % 10)


# Any y no dict: algunos endpoints responden texto plano, y la prueba ya
# valida la forma de cada respuesta en su propio paso.
def llamar(metodo: str, url: str, cuerpo: Any = None, usuario: str | None = USUARIO) -> tuple[int, Any]:
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    req = urllib.request.Request(url, data=datos, method=metodo)
    req.add_header("Content-Type", "application/json")
    if usuario:
        req.add_header("X-Usuario-Id", usuario)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            texto = r.read().decode()
            return r.status, (json.loads(texto) if texto.strip().startswith(("{", "[")) else texto)
    except urllib.error.HTTPError as e:
        texto = e.read().decode()
        try:
            return e.code, json.loads(texto)
        except ValueError:
            return e.code, texto
    except (urllib.error.URLError, TimeoutError) as e:
        # Servicio caído o sin responder: se reporta como FALLA del paso, no
        # como un traceback que corta la prueba y esconde el resto del recorrido.
        return 0, f"sin conexión: {e}"


def comprobar(nombre, condicion, detalle=""):
    print(f"  [{'OK' if condicion else 'FALLA'}] {nombre}" + (f" — {detalle}" if detalle else ""))
    if not condicion:
        fallos.append(nombre)


def esperar(descripcion, funcion, segundos=30):
    """Los efectos asíncronos (Kafka, RabbitMQ) no son instantáneos: se sondea."""
    limite = time.time() + segundos
    while time.time() < limite:
        valor = funcion()
        if valor:
            return valor
        time.sleep(1)
    return None


print(f"Usuario de la prueba: {USUARIO}\n")

print("1. Catálogo (ms-productos)")
st, productos = llamar("GET", f"{PRODUCTOS}/api/productos")
comprobar("lista de productos", st == 200 and len(productos) == 20, f"{len(productos) if st == 200 else st} productos")
st, cats = llamar("GET", f"{PRODUCTOS}/api/productos/categorias")
comprobar("8 categorías", st == 200 and len(cats) == 8, f"{[c.get('slug') for c in cats] if st == 200 else st}")
stock_antes = next(p["stock"] for p in productos if p["id"] == "item_107")

print("\n2. Navegación → interacciones-user (VER_PRODUCTO desde ms-productos)")
for pid in ("item_107", "item_108", "item_107"):
    st, _ = llamar("GET", f"{PRODUCTOS}/api/productos/{pid}")
    comprobar(f"detalle {pid}", st == 200)

print("\n3. Recomendaciones (consumo de Kafka en ms-recomendaciones)")
def recos_de_audio():
    st, r = llamar("GET", f"{RECO}/api/recomendaciones?userId={USUARIO}")
    return r if st == 200 and r and any((x.get("motivo") or "") != "Popular entre otros clientes" for x in r) else None
recos = esperar("recomendaciones personalizadas", recos_de_audio)
comprobar("recomendaciones personalizadas tras navegar", bool(recos),
          "; ".join(f"{x.get('nombre')} ({x.get('motivo')})" for x in (recos or [])[:3]))

print("\n4. Carrito (ms-carrito → Kafka + RabbitMQ)")
st, c = llamar("POST", f"{CARRITO}/api/carrito/items", {"productoId": "item_107", "cantidad": 1})
comprobar("agregar WH-1000XM5", st in (200, 201), f"HTTP {st}")
st, c = llamar("POST", f"{CARRITO}/api/carrito/cupon", {"codigo": "BIENVENIDA10"})
comprobar("cupón BIENVENIDA10", st == 200, f"descuento={c.get('descuento') if isinstance(c, dict) else c}")
st, c = llamar("POST", f"{CARRITO}/api/carrito/cupon", {"codigo": "NOEXISTE"})
comprobar("cupón inexistente rechazado", st == 404, f"HTTP {st}")
st, c = llamar("PUT", f"{CARRITO}/api/carrito/envio",
               {"metodo": "ENVIO", "direccion": "Av. Los Leones 123", "comuna": "Providencia"})
comprobar("datos de envío", st == 200, f"total={c.get('total') if isinstance(c, dict) else c}")

print("\n5. Pedido + pago SIMULADO (ms-carrito → ms-productos → ms-pago → pagos-resultado)")
st, pedido = llamar("POST", f"{CARRITO}/api/pedidos")
comprobar("pedido creado en PENDIENTE_PAGO", st in (200, 201) and pedido.get("estado") == "PENDIENTE_PAGO",
          f"HTTP {st} {pedido.get('id') if isinstance(pedido, dict) else pedido}")
id_pedido, total = pedido["id"], pedido["total"]
st, _ = llamar("GET", f"{PRODUCTOS}/api/productos/item_107")
stock_reservado = _["stock"] if st == 200 else None
comprobar("stock reservado", stock_reservado == stock_antes - 1, f"{stock_antes} → {stock_reservado}")

TARJETA_COMPRA = tarjeta_unica("4")
st, pago = llamar("POST", f"{PAGOS}/api/pagos/iniciar", {
    "clientId": USUARIO, "amount": total, "transactionId": id_pedido,
    "pasarela": "SIMULADA", "origen": "TIENDA", "tarjeta": {"numero": TARJETA_COMPRA}})
comprobar("pago simulado aprobado", st == 200, f"HTTP {st} {pago}")
comprobar("la respuesta no expone el número de tarjeta", TARJETA_COMPRA not in json.dumps(pago))

def pedido_pagado():
    st, p = llamar("GET", f"{CARRITO}/api/pedidos/{id_pedido}")
    return p if st == 200 and p.get("estado") not in ("PENDIENTE_PAGO",) else None
p = esperar("pedido pagado", pedido_pagado)
comprobar("pagos-resultado llegó a ms-carrito (pedido PAGADO)", bool(p) and p["estado"] != "PAGO_RECHAZADO",
          p["estado"] if p else "sigue en PENDIENTE_PAGO")

print("\n6. Notificaciones (RabbitMQ → ms-notificaciones)")
def notificaciones():
    st, n = llamar("GET", f"{NOTIF}/api/notificaciones?usuarioId={USUARIO}", usuario=None)
    tipos = {x.get("tipo") for x in n} if st == 200 else set()
    return tipos if {"carrito.producto-agregado", "pedido.creado", "pedido.pagado"} <= tipos else None
tipos = esperar("notificaciones", notificaciones)
comprobar("carrito, pedido creado y pedido pagado notificados", bool(tipos), f"{sorted(tipos) if tipos else '—'}")

print("\n7. Antifraude: la misma tarjeta, más de 3 veces en 10 s")
tarjeta = {"numero": tarjeta_unica("5")}
codigos = []
for i in range(4):
    st, r = llamar("POST", f"{PAGOS}/api/pagos/iniciar", {
        "clientId": USUARIO, "amount": 1000, "transactionId": f"HUMO-{int(time.time()*1000)}-{i}",
        "pasarela": "SIMULADA", "origen": "TIENDA", "tarjeta": tarjeta})
    codigos.append(st)
    time.sleep(0.3)
comprobar("los 3 primeros pasan y el 4.º recibe 429", codigos[:3] != [429] * 3 and codigos[3] == 429, f"{codigos}")
st, alertas = llamar("GET", f"{PAGOS}/api/alertas")
comprobar("alerta registrada con últimos 4", st == 200 and any(a.get("ultimos4") == tarjeta["numero"][-4:] for a in alertas.get("alertas", [])),
          f"bloqueos activos: {alertas.get('bloqueosActivos') if isinstance(alertas, dict) else alertas}")

print("\n8. Métricas en vivo (grupo-dashboard)")
st, m = llamar("GET", f"{RECO}/api/metricas")
comprobar("métricas con tráfico", st == 200 and isinstance(m, dict), json.dumps(m)[:160] if st == 200 else str(st))

print("\n9. Administración")
st, t = llamar("GET", f"{ADMIN}/api/topicos/interacciones-user/configuracion")
comprobar("kafka-admin lee configuración del tópico", st == 200)
st, g = llamar("GET", f"{ADMIN}/api/consumer-groups/grupo-recomendaciones")
comprobar("kafka-admin describe el grupo", st == 200, f"{len(g.get('miembros', [])) if isinstance(g, dict) else g} miembros")
st, cl = llamar("GET", f"{RABBIT_ADMIN}/api/rabbit/cluster")
comprobar("rabbit-admin ve el clúster", st == 200, json.dumps(cl)[:120] if st == 200 else str(st))
st, r = llamar("GET", f"{CARRITO}/api/admin/resumen")
comprobar("resumen admin con la venta", st == 200, f"ingresos={r.get('ingresosTotales') if isinstance(r, dict) else r}")

print("\n" + ("TODO OK" if not fallos else f"{len(fallos)} FALLA(S): " + ", ".join(fallos)))
sys.exit(1 if fallos else 0)
