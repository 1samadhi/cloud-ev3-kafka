#!/usr/bin/env bash
# =============================================================================
#  16-cognito-authorizer.sh — la identidad se valida en el borde
#
#  QUE PROBLEMA RESUELVE
#  Hasta la fase 3 la identidad era una cabecera: el frontend mandaba
#  X-Usuario-Id y los servicios le creian (UsuarioActual). Eso significa que
#  cualquiera con curl podia comprar, pagar o leer pedidos como otra persona:
#     curl -H "X-Usuario-Id: admin@pedidos360.cl" .../api/admin/pedidos
#
#  Con un authorizer de Cognito en el API Gateway:
#   1. el gateway valida firma, emisor y expiracion del JWT contra el JWKS del
#      user pool ANTES de que la peticion llegue a la EC2 (sin token: 401);
#   2. y REESCRIBE X-Usuario-Id con el claim `email` del token, de modo que lo
#      que el servicio lee ya no lo elige el cliente.
#  Los 7 microservicios no cambian ni una linea: siguen leyendo la cabecera.
#
#  QUE QUEDA PROTEGIDO Y QUE NO (y por que)
#  Protegido  lo que es de una persona: pedidos, pago, notificaciones y el panel
#             admin (incluida la reposicion de stock).
#  Abierto    catalogo, recomendaciones, metricas, interacciones, carrito y la
#             administracion de Kafka/RabbitMQ.
#             - El CARRITO queda abierto a proposito: la tienda deja armar el
#               carrito sin cuenta y lo traslada al iniciar sesion. Ese traslado
#               hace "vaciar el carrito del anonimo" con el token del usuario ya
#               logueado; si el gateway reescribiera X-Usuario-Id en esa ruta,
#               borraria el carrito EQUIVOCADO (el del usuario). Verificado al
#               leer TiendaProvider.ingresar().
#             - La administracion de Kafka/RabbitMQ la consume la consola, que
#               no tiene login todavia (P1): protegerla la dejaria en blanco.
#  OPTIONS    nunca lleva authorizer: el preflight del navegador viaja sin
#             Authorization y si se protege, el navegador no llega ni a mandar
#             la peticion real. Por eso este script no toca OPTIONS.
#
#  Requiere: .ev3-estado.env (del 01/10) y COGNITO_POOL_ID exportado o escrito
#  ahi mismo. Es idempotente: reutiliza el authorizer si ya existe.
# =============================================================================
set -uo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO — corre primero 01 y 10"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
: "${REGION:?}" "${API_REST:?}" "${CUENTA:?}"
API="$API_REST"
STAGE="${STAGE:-desarrollo}"
POOL="${COGNITO_POOL_ID:-${POOL_ID:-}}"
[ -n "$POOL" ] || { echo "falta COGNITO_POOL_ID (export COGNITO_POOL_ID=us-east-1_xxx)"; exit 1; }
export AWS_PAGER=""

sincr(){ tr -d '\r'; }
log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
err(){ printf '    \033[1;31mFALLA\033[0m %s\n' "$*"; }
declare -i FALLOS=0

aws_api(){  # reintenta el rate limit de escritura del management API
  local intento=0 salida
  while :; do
    if salida=$(aws apigateway "$@" --region "$REGION" --output json 2>&1 | sincr); then
      printf '%s' "$salida"; return 0
    fi
    if grep -q "TooManyRequestsException" <<< "$salida" && [ $intento -lt 8 ]; then
      intento=$((intento+1)); sleep $((intento*2)); continue
    fi
    printf '%s' "$salida" >&2; return 1
  done
}

# ----------------------------------------------------------- el authorizer
ARN="arn:aws:cognito-idp:$REGION:$CUENTA:userpool/$POOL"
log "Authorizer de Cognito sobre $POOL"

AUTH_ID=$(aws apigateway get-authorizers --region "$REGION" --rest-api-id "$API" \
  --query "items[?name=='cognito-pedidos360'].id | [0]" --output text 2>/dev/null | sincr)

if [ -z "$AUTH_ID" ] || [ "$AUTH_ID" = "None" ]; then
  AUTH_ID=$(aws_api create-authorizer --rest-api-id "$API" --name cognito-pedidos360 \
    --type COGNITO_USER_POOLS --provider-arns "$ARN" \
    --identity-source 'method.request.header.Authorization' \
    --query id --output text | sincr) || { err "no pude crear el authorizer"; exit 1; }
  ok "creado: $AUTH_ID"
else
  # El ARN cambia cuando el lab se recrea: se reapunta en vez de duplicar.
  aws_api update-authorizer --rest-api-id "$API" --authorizer-id "$AUTH_ID" \
    --patch-operations "op=replace,path=/providerARNs/0,value=$ARN" >/dev/null \
    && ok "reutilizado y reapuntado: $AUTH_ID" || err "no pude reapuntar $AUTH_ID"
fi

# ------------------------------------------------------- rutas protegidas
# ruta | verbos
PROTEGIDAS=(
  "/api/pedidos|GET,POST"
  "/api/pedidos/{id}|GET"
  "/api/pagos/iniciar|POST"
  "/api/notificaciones|GET"
  "/api/notificaciones/leidas|POST"
  "/api/notificaciones/{id}/leida|PATCH"
  "/api/admin/resumen|GET"
  "/api/admin/pedidos|GET"
  "/api/productos/admin/stock|GET"
  "/api/productos/{id}/stock|PUT"
)

declare -A IDS
while read -r RUTA ID; do
  [ -n "${RUTA:-}" ] && IDS["$RUTA"]="$ID"
done < <(aws apigateway get-resources --region "$REGION" --rest-api-id "$API" \
          --limit 500 --query 'items[].[path,id]' --output text | sincr)

log "Protegiendo ${#PROTEGIDAS[@]} rutas"
for ENTRADA in "${PROTEGIDAS[@]}"; do
  IFS='|' read -r RUTA VERBOS <<< "$ENTRADA"
  REC="${IDS[$RUTA]:-}"
  if [ -z "$REC" ]; then err "$RUTA no existe en el gateway (corre el 14)"; FALLOS+=1; continue; fi
  for V in $(tr ',' ' ' <<< "$VERBOS"); do
    if ! aws_api update-method --rest-api-id "$API" --resource-id "$REC" --http-method "$V" \
         --patch-operations "op=replace,path=/authorizationType,value=COGNITO_USER_POOLS" \
                            "op=replace,path=/authorizerId,value=$AUTH_ID" >/dev/null; then
      err "$V $RUTA update-method"; FALLOS+=1; continue
    fi
    # La reescritura de la identidad: el servicio lee el claim, no al cliente.
    if ! aws_api update-integration --rest-api-id "$API" --resource-id "$REC" --http-method "$V" \
         --patch-operations "op=replace,path=/requestParameters/integration.request.header.X-Usuario-Id,value=context.authorizer.claims.email" >/dev/null; then
      err "$V $RUTA no pude mapear el claim a X-Usuario-Id"; FALLOS+=1; continue
    fi
    printf '    %-7s %-34s token exigido + X-Usuario-Id = claims.email\n' "$V" "$RUTA"
  done
done

log "Publicando en el stage '$STAGE'"
aws_api create-deployment --rest-api-id "$API" --stage-name "$STAGE" \
  --description "authorizer de Cognito en las rutas de identidad" --query id --output text | sed 's/^/    despliegue /'

log "Resultado"
if [ "$FALLOS" -gt 0 ]; then err "$FALLOS operaciones fallaron"; else ok "listo"; fi
cat <<EOF

    Comprobacion (espera ~1 min de propagacion):
      URL=https://$API.execute-api.$REGION.amazonaws.com/$STAGE
      curl -s -o /dev/null -w '%{http_code}\\n' "\$URL/api/pedidos"            # 401
      curl -s -o /dev/null -w '%{http_code}\\n' -H "Authorization: Bearer \$ID_TOKEN" "\$URL/api/pedidos"   # 200
      # suplantacion: la cabecera ya no decide
      curl -s -H "Authorization: Bearer \$ID_TOKEN" -H "X-Usuario-Id: otro@correo.cl" "\$URL/api/pedidos"
EOF
