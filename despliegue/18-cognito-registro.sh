#!/usr/bin/env bash
# =============================================================================
#  18-cognito-registro.sh — que un usuario nuevo pueda entrar sin correo
#
#  EL PROBLEMA
#  La tienda ya tiene registro (SignUp contra el user pool), pero Cognito deja
#  al usuario en estado UNCONFIRMED y le manda un codigo por correo. En el
#  Learner Lab no hay SES fuera del sandbox: ese correo NO llega. El usuario se
#  registra, parece que todo salio bien y al entrar recibe
#  UserNotConfirmedException sin forma de confirmarse. Para una demo es peor que
#  no tener registro.
#
#  LA SOLUCION
#  Un trigger PreSignUp que autoconfirma la cuenta y marca el correo como
#  verificado. Es un gancho oficial de Cognito (no un parche del frontend) y son
#  diez lineas de Python.
#
#  EN PRODUCCION esto no se hace: se deja el codigo por correo. Queda dicho en
#  el propio codigo de la funcion, que es donde alguien lo va a leer.
#
#  Requiere: .ev3-estado.env con COGNITO_POOL_ID (lo deja el 17).
#  Idempotente: actualiza la funcion si ya existe.
# =============================================================================
set -uo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO — corre primero 01 y 17"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
: "${REGION:?}" "${CUENTA:?}" "${COGNITO_POOL_ID:?falta COGNITO_POOL_ID; corre 17-cognito-pool.sh}"
POOL="$COGNITO_POOL_ID"
NOMBRE="pedidos360-autoconfirmar"
export AWS_PAGER=""

sincr(){ tr -d '\r'; }
log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
err(){ printf '    \033[1;31mFALLA\033[0m %s\n' "$*"; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
TMP_URL=$(cygpath -m "$TMP" 2>/dev/null || echo "$TMP")

# ------------------------------------------------------------------ funcion
cat > "$TMP/funcion.py" <<'PY'
"""Trigger PreSignUp del user pool de pedidos360.

Autoconfirma al usuario que se registra y marca su correo como verificado.

POR QUE: en el AWS Academy Learner Lab no hay SES fuera del sandbox, asi que el
codigo de verificacion que Cognito manda por correo nunca llega. Sin esto, quien
se registra queda UNCONFIRMED y al iniciar sesion recibe
UserNotConfirmedException sin manera de salir de ahi.

EN PRODUCCION hay que borrar este trigger y dejar el flujo normal: el usuario
recibe el codigo y confirma su cuenta. Autoconfirmar significa que cualquiera
puede registrar una direccion que no es suya.
"""


def lambda_handler(evento, contexto):
    evento["response"]["autoConfirmUser"] = True
    if evento["request"]["userAttributes"].get("email"):
        evento["response"]["autoVerifyEmail"] = True
    return evento
PY

log "Empaquetando $NOMBRE"
# `zip` no existe en el git-bash de Windows. Python si, y viene con zipfile.
if command -v zip >/dev/null 2>&1; then
  ( cd "$TMP" && zip -q funcion.zip funcion.py ) || { err "no pude crear el zip"; exit 1; }
else
  python -c "
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], 'funcion.py')
" "$TMP_URL/funcion.zip" "$TMP_URL/funcion.py" || { err "no pude crear el zip con python"; exit 1; }
fi
[ -s "$TMP/funcion.zip" ] && ok "$(wc -c < "$TMP/funcion.zip" | tr -d ' ') bytes" || { err "el zip quedo vacio"; exit 1; }

ARN_ROL="arn:aws:iam::$CUENTA:role/LabRole"   # el lab no deja crear roles
ARN_FUNCION="arn:aws:lambda:$REGION:$CUENTA:function:$NOMBRE"

if aws lambda get-function --region "$REGION" --function-name "$NOMBRE" >/dev/null 2>&1; then
  aws lambda update-function-code --region "$REGION" --function-name "$NOMBRE" \
    --zip-file "fileb://$TMP_URL/funcion.zip" >/dev/null 2>&1 \
    && ok "funcion actualizada" || err "no pude actualizar la funcion"
else
  aws lambda create-function --region "$REGION" --function-name "$NOMBRE" \
    --runtime python3.12 --role "$ARN_ROL" --handler funcion.lambda_handler \
    --timeout 10 --memory-size 128 \
    --description "PreSignUp: autoconfirma al registrarse (el lab no envia correos)" \
    --zip-file "fileb://$TMP_URL/funcion.zip" >/dev/null 2>&1 \
    && ok "funcion creada" || { err "no pude crear la funcion"; exit 1; }
fi

# Cognito necesita permiso explicito para invocarla. Si ya existe, da
# ResourceConflictException y no es un problema.
aws lambda add-permission --region "$REGION" --function-name "$NOMBRE" \
  --statement-id cognito-presignup --action lambda:InvokeFunction \
  --principal cognito-idp.amazonaws.com \
  --source-arn "arn:aws:cognito-idp:$REGION:$CUENTA:userpool/$POOL" >/dev/null 2>&1 \
  && ok "Cognito puede invocarla" || ok "el permiso ya estaba"

# ------------------------------------------------------------------- enganche
# OJO: update-user-pool REEMPLAZA la configuracion, no la fusiona. Si no se
# repiten las opciones que ya tenia (correo como usuario verificado, politica de
# contrasena), vuelven a sus valores por defecto y el pool queda distinto del
# que creo el 17. Por eso se vuelven a pasar.
log "Enganchando el trigger al pool $POOL"
aws cognito-idp update-user-pool --region "$REGION" --user-pool-id "$POOL" \
  --lambda-config "PreSignUp=$ARN_FUNCION" \
  --auto-verified-attributes email \
  --policies '{"PasswordPolicy":{"MinimumLength":10,"RequireUppercase":true,"RequireLowercase":true,"RequireNumbers":true,"RequireSymbols":false}}' \
  >/dev/null 2>&1 && ok "PreSignUp enganchado" || { err "no pude enganchar el trigger"; exit 1; }

ACTUAL=$(aws cognito-idp describe-user-pool --region "$REGION" --user-pool-id "$POOL" \
          --query 'UserPool.LambdaConfig.PreSignUp' --output text | sincr)
[ "$ACTUAL" = "$ARN_FUNCION" ] && ok "verificado: $ACTUAL" || err "el pool dice: $ACTUAL"

cat <<EOF

    Comprobacion de punta a punta (sin tocar el navegador):
      python infra/prueba-registro.py <client-id>

    Deberia registrar una cuenta nueva, iniciar sesion con ella sin confirmar
    nada por correo, y borrarla al terminar.
EOF
