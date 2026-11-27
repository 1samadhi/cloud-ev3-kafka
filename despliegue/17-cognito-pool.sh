#!/usr/bin/env bash
# =============================================================================
#  17-cognito-pool.sh — el user pool, el app client y las cuentas de la demo
#
#  POR QUE EXISTE
#  El user pool se crea a mano en cinco llamadas, y cuando el Learner Lab caduca
#  hay que repetirlas todas: pool, app client, grupos, usuarios y contrasenas.
#  Hacerlo a mano la noche de la entrega es como se pierde una demo.
#
#  QUE DEJA LISTO
#   - user pool "pedidos360": el correo es el nombre de usuario y llega
#     verificado (no hay servidor de correo en el lab: un usuario sin verificar
#     no puede iniciar sesion y el mensaje de error no lo explica);
#   - app client "tienda-spa" SIN secreto: es una SPA, el secreto no se puede
#     guardar en el navegador. Flujos USER_PASSWORD_AUTH y REFRESH_TOKEN_AUTH;
#   - grupos "cliente" y "admin": el rol viaja en el claim cognito:groups, asi
#     que quien decide quien es admin es el pool y no una tabla del frontend;
#   - las dos cuentas de la demo, con contrasena permanente generada aca.
#
#  LAS CONTRASENAS
#  Se generan en este script y se escriben SOLO en el archivo que indica
#  SALIDA_CREDENCIALES (por defecto, fuera de los tres repos). No se imprimen en
#  pantalla y no van a git. Si el archivo ya existe se respeta y no se tocan las
#  cuentas: volver a correr el script no invalida la sesion de nadie.
#
#  Uso:
#    ./17-cognito-pool.sh                  # crea lo que falte
#    RECREAR_CUENTAS=1 ./17-cognito-pool.sh   # fuerza contrasenas nuevas
# =============================================================================
set -uo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO — corre primero 01"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
: "${REGION:?}"
export AWS_PAGER=""

NOMBRE_POOL="${NOMBRE_POOL:-pedidos360}"
SALIDA_CREDENCIALES="${SALIDA_CREDENCIALES:-$AQUI/../../CREDENCIALES-DEMO.txt}"

sincr(){ tr -d '\r'; }
log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
err(){ printf '    \033[1;31mFALLA\033[0m %s\n' "$*"; }

cog(){ aws cognito-idp "$@" --region "$REGION" 2>&1 | sincr; }

# ------------------------------------------------------------------- pool
log "User pool '$NOMBRE_POOL'"
POOL=$(aws cognito-idp list-user-pools --max-results 60 --region "$REGION" \
        --query "UserPools[?Name=='$NOMBRE_POOL'].Id | [0]" --output text | sincr)

if [ -z "$POOL" ] || [ "$POOL" = "None" ]; then
  # Sin simbolos obligatorios: la contrasena se teclea en vivo delante del
  # curso. 10 caracteres con mayuscula, minuscula y numero siguen siendo una
  # politica razonable y es lo que generan las cuentas de abajo.
  POOL=$(cog create-user-pool --pool-name "$NOMBRE_POOL" \
          --username-attributes email --auto-verified-attributes email \
          --policies '{"PasswordPolicy":{"MinimumLength":10,"RequireUppercase":true,"RequireLowercase":true,"RequireNumbers":true,"RequireSymbols":false}}' \
          --query 'UserPool.Id' --output text)
  [ "${POOL:0:9}" = "us-east-1" ] || { err "no pude crear el pool: $POOL"; exit 1; }
  ok "creado $POOL"
else
  ok "ya existia $POOL"
fi

# --------------------------------------------------------------- app client
log "App client 'tienda-spa'"
CLIENTE=$(aws cognito-idp list-user-pool-clients --user-pool-id "$POOL" --max-results 60 \
           --region "$REGION" --query "UserPoolClients[?ClientName=='tienda-spa'].ClientId | [0]" \
           --output text | sincr)
if [ -z "$CLIENTE" ] || [ "$CLIENTE" = "None" ]; then
  # OJO: --read-attributes/--write-attributes con "name" hace fallar la llamada
  # con InvalidParameterException ("Invalid write attributes specified"). Los
  # valores por defecto ya incluyen los atributos estandar, asi que no se pasan.
  CLIENTE=$(cog create-user-pool-client --user-pool-id "$POOL" --client-name tienda-spa \
             --no-generate-secret \
             --explicit-auth-flows ALLOW_USER_PASSWORD_AUTH ALLOW_USER_SRP_AUTH ALLOW_REFRESH_TOKEN_AUTH \
             --access-token-validity 8 --id-token-validity 8 --refresh-token-validity 30 \
             --token-validity-units '{"AccessToken":"hours","IdToken":"hours","RefreshToken":"days"}' \
             --query 'UserPoolClient.ClientId' --output text)
  [ ${#CLIENTE} -gt 20 ] || { err "no pude crear el app client: $CLIENTE"; exit 1; }
  ok "creado"
else
  ok "ya existia"
fi

# ------------------------------------------------------------------ grupos
log "Grupos"
for G in cliente admin; do
  cog create-group --user-pool-id "$POOL" --group-name "$G" >/dev/null
  printf '    %s\n' "$G"
done

# ----------------------------------------------------------------- cuentas
log "Cuentas de la demo"
if [ -f "$SALIDA_CREDENCIALES" ] && [ "${RECREAR_CUENTAS:-0}" != "1" ]; then
  ok "$(basename "$SALIDA_CREDENCIALES") ya existe: no se tocan las cuentas (RECREAR_CUENTAS=1 para forzar)"
else
  : > "$SALIDA_CREDENCIALES"
  {
    echo "Cuentas de demostracion del user pool de Cognito ($NOMBRE_POOL)"
    echo "Pool $POOL - cliente $CLIENTE - region $REGION"
    echo "Archivo local, fuera de los repos git. NO subir."
    echo
  } >> "$SALIDA_CREDENCIALES"

  # Palabras faciles de teclear + 4 digitos: cumple la politica y se dicta en voz
  # alta sin errores si hace falta.
  PALABRAS=(Andes Maipo Elqui Lautaro Chiloe Rapel Choapa Limari Cachapoal Itata)
  clave(){
    local a=${PALABRAS[$((RANDOM % ${#PALABRAS[@]}))]} b=${PALABRAS[$((RANDOM % ${#PALABRAS[@]}))]}
    printf '%s%s%04d' "$a" "$(tr '[:upper:]' '[:lower:]' <<< "$b")" "$((RANDOM % 10000))"
  }

  CUENTAS=("ana@correo.cl|Ana Rojas|cliente" "admin@pedidos360.cl|Administrador|admin")
  for ENTRADA in "${CUENTAS[@]}"; do
    IFS='|' read -r EMAIL NOMBRE GRUPO <<< "$ENTRADA"
    PW=$(clave)
    cog admin-create-user --user-pool-id "$POOL" --username "$EMAIL" --message-action SUPPRESS \
      --user-attributes "Name=email,Value=$EMAIL" Name=email_verified,Value=true "Name=name,Value=$NOMBRE" >/dev/null
    if ! R=$(cog admin-set-user-password --user-pool-id "$POOL" --username "$EMAIL" --password "$PW" --permanent); then
      err "$EMAIL: $R"; continue
    fi
    cog admin-add-user-to-group --user-pool-id "$POOL" --username "$EMAIL" --group-name "$GRUPO" >/dev/null
    printf '%-24s %s   (rol %s)\n' "$EMAIL" "$PW" "$GRUPO" >> "$SALIDA_CREDENCIALES"
    printf '    %-24s lista (rol %s)\n' "$EMAIL" "$GRUPO"
  done
  ok "contrasenas en $SALIDA_CREDENCIALES (no se muestran en pantalla)"
fi

# ------------------------------------------------------------------ estado
for CLAVE in "COGNITO_POOL_ID=$POOL" "COGNITO_CLIENT_ID=$CLIENTE"; do
  NOMBRE="${CLAVE%%=*}"
  if grep -q "^$NOMBRE=" "$ESTADO"; then
    sed -i "s|^$NOMBRE=.*|$CLAVE|" "$ESTADO"
  else
    echo "$CLAVE" >> "$ESTADO"
  fi
done

log "Listo"
cat <<EOF

    Para el .env de la instancia:
      COGNITO_REGION=$REGION
      COGNITO_POOL_ID=$POOL
      COGNITO_CLIENT_ID=$CLIENTE

    Despues:
      ./16-cognito-authorizer.sh          # exige el token en las rutas de identidad
      docker compose up -d --force-recreate tienda
EOF
