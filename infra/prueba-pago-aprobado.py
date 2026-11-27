# -*- coding: utf-8 -*-
"""P6 — evidencia de un pago Webpay APROBADO, verificada en AWS.

El paso humano es tipear la tarjeta de prueba en la pagina de Transbank: eso no
se automatiza (ni se debe). Lo que hace este script es todo lo de alrededor,
que es donde se demuestra el criterio:

  1. toma una foto del estado ANTES (que pedidos PAGADO ya existian), para que
     despues no se pueda confundir un pedido viejo con el de la demo;
  2. VIGILA hasta que aparezca un pedido PAGADO nuevo (o hasta que se agote el
     plazo), leyendo por el API Gateway con la sesion real de la cuenta demo;
  3. comprueba lo que de verdad prueba el criterio:
       - el pedido quedo PAGADO (no PENDIENTE_PAGO ni PAGO_RECHAZADO),
       - trae codigoAutorizacion, que lo emite Transbank y no el sistema,
       - trae pagadoEn,
       - el historial registra la transicion PENDIENTE_PAGO -> PAGADO, o sea
         que paso por la maquina de estados y no por un UPDATE a mano,
       - llego la notificacion PEDIDO_PAGADO de ese pedido (RabbitMQ), que es
         la mitad asincrona del flujo;
  4. escribe la evidencia en evidencias/NN-pago-aprobado-<fecha>.md con los
     datos crudos, para no tener que creerle a nadie.

Las contrasenas se leen del archivo local de credenciales y NUNCA se imprimen ni
se escriben en la evidencia.

Uso:
  python infra/prueba-pago-aprobado.py <client-id>              # vigila 15 min
  python infra/prueba-pago-aprobado.py <client-id> --espera 300 # vigila 5 min
  python infra/prueba-pago-aprobado.py <client-id> --ahora      # sin vigilar:
      revisa el ultimo pago aprobado que ya exista (por si el pago ya se hizo)
"""
import json, os, re, subprocess, sys, time, urllib.error, urllib.request
from datetime import datetime

GW = "https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo"
HOST = "18.211.30.140"
ORIGIN = "http://%s:8091" % HOST
REGION = "us-east-1"
CREDS = r"C:/Users/diego/pedidos360/CREDENCIALES-DEMO.txt"
CLIENTE = "ana@correo.cl"
AQUI = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVID = os.path.join(AQUI, "evidencias")

fallos = []


def comprobar(nombre, condicion, detalle=""):
    print("  [%s] %s%s" % ("OK " if condicion else "MAL", nombre,
                           (" — " + detalle) if detalle else ""))
    if not condicion:
        fallos.append(nombre)
    return condicion


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
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
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


def pedidos(token):
    cod, cuerpo = pedir(GW + "/api/pedidos", token=token, usuario=CLIENTE)
    if cod != 200:
        return None, "HTTP %s %s" % (cod, cuerpo[:200])
    try:
        return json.loads(cuerpo), None
    except Exception as e:
        return None, "JSON %s" % e


def pagados(lista):
    """Pedidos con el pago ya aprobado.

    OJO: no se filtra por estado == "PAGADO". PAGADO es un estado de PASO: en
    cuanto el pedido avanza (PREPARANDO, ENVIADO, ENTREGADO...) deja de ser
    PAGADO y un filtro por estado lo pierde. Lo que no se borra nunca es
    `pagadoEn`. Esto se descubrio con P-16, que estaba pagado y ENTREGADO y el
    filtro por estado reportaba "0 pedidos pagados".
    """
    return {p["id"]: p for p in lista if p.get("pagadoEn")}


RECHAZADOS = ("PENDIENTE_PAGO", "PAGO_RECHAZADO")


def pasarela_en_logs(orden):
    """Que pasarela cobro esta orden, segun el log de ms-pago en la EC2.

    Es la UNICA forma de distinguir Webpay de la pasarela simulada: las dos
    dejan `codigoAutorizacion` en el pedido, asi que el pedido por si solo no
    prueba que el pago haya pasado por Transbank — y P6 es exactamente eso.
    """
    estado = os.path.join(AQUI, "despliegue", ".ev3-estado.env")
    conf = {}
    try:
        for linea in open(estado, encoding="utf-8"):
            if "=" in linea and not linea.strip().startswith("#"):
                k, v = linea.strip().split("=", 1)
                conf[k] = v
    except OSError:
        return None, "no pude leer %s" % estado
    inst, region = conf.get("INSTANCIA"), conf.get("REGION", "us-east-1")
    if not inst:
        return None, "falta INSTANCIA en .ev3-estado.env"
    # El `aws` del PATH en Windows es un wrapper bash: desde Python nativo hay
    # que invocar el interprete (misma trampa que en el doc 18).
    aws = [sys.executable, "-m", "awscli"]
    cmd = ('docker logs ms-pago --since 2h 2>&1 | grep -E "orden=%s( |$)" | tail -20' % orden)
    try:
        cid = subprocess.run(
            aws + ["ssm", "send-command", "--region", region, "--instance-ids", inst,
                   "--document-name", "AWS-RunShellScript",
                   "--parameters", json.dumps({"commands": [cmd]}),
                   "--query", "Command.CommandId", "--output", "text"],
            capture_output=True, text=True, timeout=90).stdout.strip()
        if not cid:
            return None, "SSM no devolvio command id"
        for _ in range(12):
            time.sleep(5)
            r = subprocess.run(
                aws + ["ssm", "get-command-invocation", "--region", region,
                       "--command-id", cid, "--instance-id", inst,
                       "--query", "{s:Status,o:StandardOutputContent}", "--output", "json"],
                capture_output=True, text=True, timeout=60)
            try:
                d = json.loads(r.stdout)
            except Exception:
                continue
            if d.get("s") == "Success":
                return d.get("o", ""), None
            if d.get("s") in ("Failed", "Cancelled", "TimedOut"):
                return None, "SSM %s" % d.get("s")
        return None, "SSM no termino a tiempo"
    except Exception as e:
        return None, "SSM %s" % e


# --------------------------------------------------------------- argumentos
args = [a for a in sys.argv[1:]]
AHORA = "--ahora" in args
args = [a for a in args if a != "--ahora"]
ESPERA = 900
if "--espera" in args:
    i = args.index("--espera")
    ESPERA = int(args[i + 1])
    del args[i:i + 2]
CLIENT_ID = args[0] if args else os.environ.get("COGNITO_CLIENT_ID", "")
if not CLIENT_ID:
    sys.exit("falta el client id: python infra/prueba-pago-aprobado.py <client-id>")

cuentas = credenciales()
if CLIENTE not in cuentas:
    sys.exit("no encuentro la clave de %s en %s" % (CLIENTE, CREDS))

print("P6 — pago Webpay aprobado, verificado por el API Gateway en AWS")
print("     cuenta: %s   gateway: %s\n" % (CLIENTE, GW))

token = login(CLIENTE, cuentas[CLIENTE], CLIENT_ID)
lista, err = pedidos(token)
if lista is None:
    sys.exit("no pude leer los pedidos: %s" % err)

antes = pagados(lista)
print("Estado ANTES: %d pedidos, %d ya estaban PAGADO%s" % (
    len(lista), len(antes), (" (" + ", ".join(sorted(antes)) + ")") if antes else ""))

# ------------------------------------------------------------------ vigilar
objetivo = None
if AHORA:
    if not antes:
        sys.exit("no hay ningun pago aprobado todavia; corre sin --ahora y paga")
    objetivo = max(antes.values(), key=lambda p: p.get("pagadoEn") or "")
    print("\n--ahora: reviso el ultimo pago aprobado que ya existia: %s" % objetivo["id"])
else:
    print("\nAhora anda a http://%s:8091 , compra con Webpay Plus y tipea la" % HOST)
    print("tarjeta de prueba en la pagina de Transbank. Vigilo hasta %d s." % ESPERA)
    print("(la sesion del navegador tiene que ser la de %s)\n" % CLIENTE)
    inicio = time.time()
    while time.time() - inicio < ESPERA:
        time.sleep(6)
        lista, err = pedidos(token)
        if lista is None:
            print("    ...no pude leer (%s), sigo" % err)
            continue
        nuevos = {k: v for k, v in pagados(lista).items() if k not in antes}
        if nuevos:
            objetivo = list(nuevos.values())[0]
            print("    pedido PAGADO nuevo: %s" % objetivo["id"])
            break
        print("    ...esperando (%3ds) — %d pedidos, %d PAGADO" % (
            int(time.time() - inicio), len(lista), len(pagados(lista))))
    if objetivo is None:
        sys.exit("\nse agoto el plazo sin un pedido PAGADO nuevo. "
                 "Si el pago quedo a medias, mira el estado del pedido en la tienda.")

# ------------------------------------------------------- lo que hay que probar
pid = objetivo["id"]
print("\nComprobaciones sobre %s" % pid)
cod, cuerpo = pedir(GW + "/api/pedidos/" + pid, token=token, usuario=CLIENTE)
detalle = json.loads(cuerpo) if cod == 200 else objetivo
comprobar("el detalle se lee por el gateway", cod == 200, "HTTP %s" % cod)
comprobar("el pago quedo aprobado (no PENDIENTE_PAGO ni PAGO_RECHAZADO)",
          detalle.get("estado") not in RECHAZADOS, str(detalle.get("estado")))
comprobar("trae codigoAutorizacion", bool(detalle.get("codigoAutorizacion")),
          str(detalle.get("codigoAutorizacion")))
comprobar("trae pagadoEn", bool(detalle.get("pagadoEn")), str(detalle.get("pagadoEn")))
hist = [h.get("estado") for h in (detalle.get("historial") or [])]
comprobar("el historial registra PENDIENTE_PAGO -> PAGADO",
          "PENDIENTE_PAGO" in hist and "PAGADO" in hist, " -> ".join(hist) or "(vacio)")
comprobar("el total es coherente",
          detalle.get("total") == (detalle.get("subtotal", 0) - detalle.get("descuento", 0)
                                   + detalle.get("costoEnvio", 0)),
          "sub %s - desc %s + envio %s = total %s" % (
              detalle.get("subtotal"), detalle.get("descuento"),
              detalle.get("costoEnvio"), detalle.get("total")))

print("\nQue pasarela cobro: Webpay o la simulada (log de ms-pago en la EC2)")
log_pago, err_log = pasarela_en_logs(pid)
es_webpay = bool(log_pago) and "pasarela=WEBPAY" in log_pago
comprobar("el cobro paso por WEBPAY (Transbank), no por la pasarela simulada",
          es_webpay,
          ("pasarela=SIMULADA en el log: esto NO cierra P6" if log_pago and not es_webpay
           else (err_log or "sin lineas para orden=%s" % pid)))
if log_pago:
    for linea in [l for l in log_pago.splitlines() if "pasarela=" in l or "Pasarela" in l][:4]:
        print("      | %s" % linea.strip()[:150])

print("\nLa mitad asincrona: la notificacion por RabbitMQ")
notif = None
for intento in range(10):
    cod, cuerpo = pedir(GW + "/api/notificaciones", token=token, usuario=CLIENTE)
    if cod == 200:
        try:
            todas = json.loads(cuerpo)
        except Exception:
            todas = []
        for n in (todas if isinstance(todas, list) else todas.get("contenido", [])):
            if n.get("idPedido") == pid and "PAGADO" in str(n.get("tipo", "")).upper():
                notif = n
                break
    if notif:
        break
    time.sleep(4)
comprobar("llego la notificacion PEDIDO_PAGADO de %s" % pid, notif is not None,
          (notif.get("titulo") or notif.get("mensaje") or "") if notif else
          "no aparecio en ~40 s (mira la DLQ: GET :8088/api/notificaciones/fallidas)")

# ---------------------------------------------------------------- evidencia
os.makedirs(EVID, exist_ok=True)
existentes = [f for f in os.listdir(EVID) if re.match(r"\d\d-", f)]
num = max([int(f[:2]) for f in existentes] or [56]) + 1
sello = datetime.now().strftime("%Y%m%d-%H%M")
ruta = os.path.join(EVID, "%02d-pago-aprobado-%s.md" % (num, sello))

estado = "APROBADO Y VERIFICADO" if not fallos else "CON %d FALLO(S)" % len(fallos)
with open(ruta, "w", encoding="utf-8") as f:
    f.write("# %02d · P6 — pago Webpay aprobado: %s\n\n" % (num, estado))
    f.write("Fecha: **%s**. Medido **en AWS** (`%s`), por el **API Gateway**\n"
            "(`%s`) y con la sesion real de `%s` emitida por Cognito.\n\n"
            % (datetime.now().strftime("%d-%m-%Y %H:%M"), HOST, GW, CLIENTE))
    f.write("El pago se tipeo a mano en la pagina de Transbank (tarjeta de prueba);\n"
            "todo lo demas lo verifico `infra/prueba-pago-aprobado.py`.\n\n")
    f.write("## Lo que se comprueba y por que\n\n")
    f.write("| Comprobacion | Resultado |\n|---|---|\n")
    f.write("| Pedido `%s` leido por el gateway | HTTP %s |\n" % (pid, cod if cod else 200))
    f.write("| Estado | **%s** |\n" % detalle.get("estado"))
    f.write("| **Pasarela que cobro** (log de ms-pago) | **%s** |\n"
            % ("WEBPAY — Transbank" if es_webpay else "NO es Webpay: " +
               ("SIMULADA" if log_pago and "SIMULADA" in log_pago else "indeterminada")))
    f.write("| `codigoAutorizacion` | `%s` |\n" % detalle.get("codigoAutorizacion"))
    f.write("| `pagadoEn` | `%s` |\n" % detalle.get("pagadoEn"))
    f.write("| Historial (paso por la maquina de estados) | `%s` |\n" % " -> ".join(hist))
    f.write("| Total = subtotal - descuento + envio | %s - %s + %s = **%s** |\n" % (
        detalle.get("subtotal"), detalle.get("descuento"),
        detalle.get("costoEnvio"), detalle.get("total")))
    f.write("| Notificacion `PEDIDO_PAGADO` por RabbitMQ | %s |\n"
            % ("**llego**: " + str(notif.get("titulo") or notif.get("mensaje"))
               if notif else "**NO llego**"))
    if fallos:
        f.write("\n## Fallos\n\n")
        for x in fallos:
            f.write("- %s\n" % x)
    f.write("\n## Pedido crudo\n\n```json\n%s\n```\n"
            % json.dumps(detalle, indent=2, ensure_ascii=False))
    if notif:
        f.write("\n## Notificacion cruda\n\n```json\n%s\n```\n"
                % json.dumps(notif, indent=2, ensure_ascii=False))
    if log_pago:
        f.write("\n## Log de ms-pago para esta orden (la EC2, por SSM)\n\n```\n%s\n```\n"
                % log_pago.strip())
    f.write("\n## No verificado\n\n")
    f.write("- La captura de pantalla del `/callback?estado=AUTHORIZED...` y de\n"
            "  \"Mis pedidos\" se toma a mano y va en `evidencias/ev3-docker/`.\n")
    f.write("- Que el evento viajara por el topico `pagos-resultado` se deduce de que\n"
            "  el pedido quedo pagado (solo ese consumidor lo cambia) y de la linea\n"
            "  `Resultado publicado topico=pagos-resultado` del log; no se leyo el\n"
            "  topico directamente.\n")

print("\nEvidencia escrita en: %s" % ruta)
print("\n" + ("TODO OK" if not fallos else "FALLARON %d: %s" % (len(fallos), ", ".join(fallos))))
sys.exit(0 if not fallos else 1)
