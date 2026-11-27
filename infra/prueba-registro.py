# -*- coding: utf-8 -*-
"""Registro de punta a punta contra el user pool, sin tocar el navegador.

Comprueba lo que la tienda hace cuando alguien crea una cuenta:
  1. SignUp con correo, nombre y contrasena;
  2. que quede CONFIRMADO sin codigo por correo (trigger PreSignUp: en el lab
     no hay SES y el codigo nunca llegaria);
  3. que pueda iniciar sesion inmediatamente y que el ID token traiga los claims
     que la tienda necesita (email, name);
  4. que un segundo registro con el mismo correo sea rechazado;
  5. que una contrasena debil sea rechazada por la politica del pool.

La cuenta de prueba se borra al terminar, incluso si algo falla.

Uso: python infra/prueba-registro.py <client-id> [--pool us-east-1_xxx]
"""
import base64, json, subprocess, sys, time, urllib.error, urllib.request

REGION = "us-east-1"
# El `aws` del PATH en Windows es un wrapper bash: desde Python nativo hay que
# invocar el interprete, no el comando.
AWS = [r"C:/Users/diego/AppData/Local/Programs/Python/Python312/python.exe", "-m", "awscli"]

fallos = []


def comprobar(nombre, condicion, detalle=""):
    print("  [%s] %s%s" % ("OK " if condicion else "MAL", nombre, (" — " + detalle) if detalle else ""))
    if not condicion:
        fallos.append(nombre)


def cognito(operacion, carga):
    req = urllib.request.Request("https://cognito-idp.%s.amazonaws.com/" % REGION,
                                 method="POST", data=json.dumps(carga).encode())
    req.add_header("Content-Type", "application/x-amz-json-1.1")
    req.add_header("X-Amz-Target", "AWSCognitoIdentityProviderService." + operacion)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return 200, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        cuerpo = json.loads(e.read() or b"{}")
        tipo = (cuerpo.get("__type") or "").split("#")[-1]
        return e.code, {"tipo": tipo, "mensaje": cuerpo.get("message", "")}


def aws(*args):
    p = subprocess.run([*AWS, *args, "--region", REGION, "--output", "json"],
                       capture_output=True, text=True)
    return p.returncode, p.stdout.strip(), p.stderr.strip()


CLIENT = sys.argv[1]
POOL = None
if "--pool" in sys.argv:
    POOL = sys.argv[sys.argv.index("--pool") + 1]
else:
    cod, out, _ = aws("cognito-idp", "list-user-pools", "--max-results", "60")
    if cod == 0:
        POOL = next((p["Id"] for p in json.loads(out)["UserPools"] if p["Name"] == "pedidos360"), None)

CORREO = "prueba-registro-%d@correo.cl" % int(time.time())
CLAVE = "Cordillera2026"   # cumple la politica: 10+, mayuscula, minuscula, numero

try:
    print("1. Registro de una cuenta nueva (lo que hace la tienda)")
    cod, r = cognito("SignUp", {
        "ClientId": CLIENT, "Username": CORREO, "Password": CLAVE,
        "UserAttributes": [{"Name": "email", "Value": CORREO}, {"Name": "name", "Value": "Persona de Prueba"}],
    })
    comprobar("SignUp aceptado", cod == 200, str(r)[:90])
    comprobar("queda CONFIRMADA sin codigo por correo", cod == 200 and r.get("UserConfirmed") is True,
              "UserConfirmed=%s" % (r.get("UserConfirmed") if cod == 200 else "-"))

    print("\n2. Inicia sesion de inmediato")
    cod, r = cognito("InitiateAuth", {
        "AuthFlow": "USER_PASSWORD_AUTH", "ClientId": CLIENT,
        "AuthParameters": {"USERNAME": CORREO, "PASSWORD": CLAVE},
    })
    comprobar("login con la cuenta recien creada", cod == 200 and "AuthenticationResult" in r,
              r.get("tipo", "") if cod != 200 else "")
    if cod == 200:
        carga = r["AuthenticationResult"]["IdToken"].split(".")[1]
        carga += "=" * (-len(carga) % 4)
        claims = json.loads(base64.urlsafe_b64decode(carga))
        comprobar("el ID token trae email y name", claims.get("email") == CORREO and claims.get("name") == "Persona de Prueba",
                  str({k: claims.get(k) for k in ("email", "name", "email_verified")}))

    print("\n3. El mismo correo dos veces")
    cod, r = cognito("SignUp", {
        "ClientId": CLIENT, "Username": CORREO, "Password": CLAVE,
        "UserAttributes": [{"Name": "email", "Value": CORREO}],
    })
    comprobar("rechaza el correo repetido", r.get("tipo") == "UsernameExistsException", r.get("tipo", str(cod)))

    print("\n4. Contrasena debil")
    cod, r = cognito("SignUp", {
        "ClientId": CLIENT, "Username": "debil-%d@correo.cl" % int(time.time()), "Password": "123",
        "UserAttributes": [{"Name": "email", "Value": "debil@correo.cl"}],
    })
    # Cognito usa InvalidPasswordException cuando la clave incumple la politica,
    # pero InvalidParameterException cuando ni llega al minimo de longitud. Los
    # dos son el rechazo que se busca; la tienda traduce ambos.
    comprobar("rechaza la contrasena debil",
              r.get("tipo") in ("InvalidPasswordException", "InvalidParameterException"),
              r.get("tipo", str(cod)))
finally:
    if POOL:
        aws("cognito-idp", "admin-delete-user", "--user-pool-id", POOL, "--username", CORREO)
        print("\n(cuenta de prueba borrada)")

print()
print("TODO OK" if not fallos else "FALLARON %d: %s" % (len(fallos), ", ".join(fallos)))
sys.exit(1 if fallos else 0)
