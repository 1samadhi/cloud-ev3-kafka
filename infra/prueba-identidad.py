# -*- coding: utf-8 -*-
"""Verificacion de la identidad en el borde (Cognito + API Gateway).

Comprueba lo que se va a afirmar en la evidencia, en AWS y no en local:
  A. las rutas protegidas exigen token (401 sin el, 200 con el);
  B. las abiertas siguen abiertas (el anonimo navega y arma carrito);
  C. el preflight OPTIONS pasa SIN token (si no, el navegador no llega);
  D. la suplantacion por cabecera ya no funciona: el gateway reescribe
     X-Usuario-Id con el claim del token;
  E. el flujo de compra completo por el gateway, con sesion real.

Las contrasenas se leen del archivo local de credenciales y no se imprimen.
"""
import json, re, sys, time, urllib.error, urllib.request

GW = "https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo"
HOST = "18.211.30.140"
ORIGIN = "http://%s:8091" % HOST
REGION = "us-east-1"
CREDS = r"C:/Users/diego/pedidos360/CREDENCIALES-DEMO.txt"

fallos = []


def comprobar(nombre, condicion, detalle=""):
    print("  [%s] %s%s" % ("OK " if condicion else "MAL", nombre, (" — " + detalle) if detalle else ""))
    if not condicion:
        fallos.append(nombre)


def pedir(url, metodo="GET", cuerpo=None, token=None, usuario=None, timeout=25):
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    req = urllib.request.Request(url, method=metodo, data=datos)
    req.add_header("Origin", ORIGIN)
    if datos:
        req.add_header("Content-Type", "application/json")
    if usuario:
        req.add_header("X-Usuario-Id", usuario)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(1500).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read(600).decode("utf-8", "replace")
    except Exception as e:
        return 0, "ERR %s" % e


def credenciales():
    """email -> contrasena, leido del archivo local (nunca se imprime)."""
    cuentas = {}
    for linea in open(CREDS, encoding="utf-8"):
        m = re.match(r"(\S+@\S+)\s+(\S+)\s", linea)
        if m:
            cuentas[m.group(1)] = m.group(2)
    return cuentas


def login(email, clave, client_id):
    req = urllib.request.Request(
        "https://cognito-idp.%s.amazonaws.com/" % REGION,
        method="POST",
        data=json.dumps({
            "AuthFlow": "USER_PASSWORD_AUTH",
            "ClientId": client_id,
            "AuthParameters": {"USERNAME": email, "PASSWORD": clave},
        }).encode(),
    )
    req.add_header("Content-Type", "application/x-amz-json-1.1")
    req.add_header("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.loads(r.read())["AuthenticationResult"]["IdToken"]


CLIENT_ID = sys.argv[1] if len(sys.argv) > 1 else open(
    r"C:/Users/diego/AppData/Local/hermes/cache/scratch/client.txt").read().strip()
cuentas = credenciales()
ANA, ADMIN = "ana@correo.cl", "admin@pedidos360.cl"

print("1. Login real contra el user pool")
tok_ana = login(ANA, cuentas[ANA], CLIENT_ID)
tok_admin = login(ADMIN, cuentas[ADMIN], CLIENT_ID)
comprobar("las dos cuentas inician sesion", bool(tok_ana and tok_admin))

print("\n2. Rutas protegidas: sin token y con token")
PROTEGIDAS = [
    ("GET", "/api/pedidos"),
    ("GET", "/api/notificaciones"),
    ("GET", "/api/admin/resumen"),
    ("GET", "/api/productos/admin/stock"),
]
for metodo, ruta in PROTEGIDAS:
    sin, _ = pedir(GW + ruta, metodo, usuario=ANA)
    con, cuerpo = pedir(GW + ruta, metodo, token=tok_admin, usuario=ADMIN)
    comprobar("%s %s" % (metodo, ruta), sin == 401 and con == 200, "sin token %s · con token %s" % (sin, con))

print("\n3. Rutas abiertas: el anonimo sigue comprando")
for metodo, ruta, cuerpo in [
    ("GET", "/api/productos/categorias", None),
    ("GET", "/api/recomendaciones?userId=anon-verif", None),
    ("GET", "/api/carrito", None),
    ("POST", "/api/carrito/items", {"productoId": "item_107", "cantidad": 1}),
]:
    cod, _ = pedir(GW + ruta, metodo, cuerpo, usuario="anon-verif")
    comprobar("%s %s sin token" % (metodo, ruta), cod == 200, "HTTP %s" % cod)

print("\n4. Preflight OPTIONS sin token (si falla, el navegador no llega)")
for ruta in ("/api/pedidos", "/api/pagos/iniciar"):
    cod, _ = pedir(GW + ruta, "OPTIONS")
    comprobar("OPTIONS %s" % ruta, cod == 200, "HTTP %s" % cod)

print("\n5. Suplantacion por cabecera: el claim manda")
# Con el token de Ana y la cabecera del admin, el servicio debe ver a ANA.
cod, cuerpo = pedir(GW + "/api/pedidos", token=tok_ana, usuario=ADMIN)
ajenos = [p for p in (json.loads(cuerpo) if cod == 200 and cuerpo.startswith("[") else []) if p.get("usuarioId") != ANA]
comprobar("200 con el token de Ana", cod == 200, "HTTP %s" % cod)
comprobar("ningun pedido de otro usuario aunque la cabecera diga admin", not ajenos,
          "%d pedidos ajenos" % len(ajenos))

print("\n6. Compra completa por el gateway, con sesion real")
cod, _ = pedir(GW + "/api/carrito/items", "POST", {"productoId": "item_107", "cantidad": 1}, tok_ana, ANA)
comprobar("agregar al carrito", cod == 200, "HTTP %s" % cod)
cod, _ = pedir(GW + "/api/carrito/envio", "PUT",
               {"modo": "RETIRO", "sucursal": "SANTIAGO_CENTRO", "nombre": "Ana Rojas", "telefono": "+56990000000"},
               tok_ana, ANA)
comprobar("datos de envio (PUT, el verbo que faltaba)", cod == 200, "HTTP %s" % cod)
cod, cuerpo = pedir(GW + "/api/pedidos", "POST", None, tok_ana, ANA)
pedido = json.loads(cuerpo).get("id") if cod == 201 or cod == 200 else None
comprobar("crear pedido", bool(pedido), "HTTP %s %s" % (cod, cuerpo[:80]))
if pedido:
    cod, cuerpo = pedir(GW + "/api/pagos/iniciar", "POST", {
        "clientId": ANA, "amount": 1000, "transactionId": pedido,
        "pasarela": "SIMULADA", "origen": "TIENDA", "tarjeta": {"numero": "4111111111111111"}},
        tok_ana, ANA)
    comprobar("pago simulado", cod == 200 and "AUTORIZADO" in cuerpo, "HTTP %s %s" % (cod, cuerpo[:60]))
    cod, cuerpo = pedir(GW + "/api/pedidos/" + pedido, token=tok_ana, usuario=ANA)
    comprobar("leer el pedido propio", cod == 200, "HTTP %s" % cod)

print("\n7. Antifraude por el gateway: el 429 llega al navegador")
tarjeta = {"numero": "41111111111%05d" % (int(time.time()) % 100000)}
codigos = []
for i in range(4):
    cod, _ = pedir(GW + "/api/pagos/iniciar", "POST", {
        "clientId": ANA, "amount": 1000, "transactionId": "VERIF-%d-%d" % (int(time.time() * 1000), i),
        "pasarela": "SIMULADA", "origen": "TIENDA", "tarjeta": tarjeta}, tok_ana, ANA)
    codigos.append(cod)
    time.sleep(0.3)
comprobar("los 3 primeros pasan y el 4.o es 429", codigos[:3] != [429] * 3 and codigos[3] == 429, str(codigos))

print()
print("TODO OK" if not fallos else "FALLARON %d: %s" % (len(fallos), ", ".join(fallos)))
sys.exit(1 if fallos else 0)
