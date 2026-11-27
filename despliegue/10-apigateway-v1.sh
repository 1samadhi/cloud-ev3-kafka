#!/usr/bin/env bash
# =============================================================================
#  10-apigateway-v1.sh — API Gateway REST con el contrato /v1 (reemplaza al 08)
#
#  CONTRATO PUBLICO (lo unico que el frontend necesita conocer):
#
#     POST /v1/pagos/iniciar            ms-pago            :8083
#     POST /v1/pagos/commit             ms-pago
#     GET  /v1/alertas                  ms-pago
#     POST /v1/interacciones            ms-recomendaciones :8084
#     GET  /v1/recomendaciones          ms-recomendaciones
#     GET  /v1/metricas                 ms-recomendaciones
#     GET  /v1/admin/topicos            ms-kafka-admin     :8085
#     GET  /v1/admin/cluster            ms-kafka-admin
#     GET  /v1/admin/consumer-groups    ms-kafka-admin
#
#  DECISIONES DE DISENO
#
#  * Sin {proxy+}: cada recurso y metodo declarado. Es la observacion textual
#    del docente en la evaluacion anterior ("No usar Proxy en backend") y ademas
#    permite poner autenticacion o limites por ruta, cosa imposible con un
#    comodin que manda todo al mismo lado.
#
#  * Sin el prefijo /api: dentro de un gateway que YA es la API, ese segmento
#    es ruido. En los microservicios si distingue de /actuator, pero esa es una
#    ruta interna que el cliente no deberia ver.
#
#  * Con /v1: permite publicar una v2 sin romper a quien consume la v1.
#
#  * /admin agrupado: son operaciones de infraestructura, no de la tienda.
#    Agrupadas se les puede exigir autenticacion sin tocar el resto.
#
#  * El frontend usa UNA sola URL base. Antes tenia tres (una por microservicio),
#    lo que significaba que conocia la topologia interna del backend: si manana
#    se parte un servicio en dos, habria que tocar el front. El gateway existe
#    justamente para esconder eso.
#
#  Uso:  ./10-apigateway-v1.sh
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

NOMBRE_API="pedidos360-v1"
STAGE="desarrollo"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
# El AWS CLI de Windows es un binario NATIVO: no entiende las rutas MSYS que
# devuelve mktemp (/tmp/...) y los file:// de abajo fallan con "Unable to load
# paramfile". cygpath -m da el equivalente C:/... con barras normales; en Linux,
# donde se escribio este script, cygpath no existe y se usa la ruta tal cual.
TMP_URL=$(cygpath -m "$TMP" 2>/dev/null || echo "$TMP")

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
err(){ printf '    \033[1;31mFALLO\033[0m %s\n' "$*"; }

IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
[ "$IP" != "None" ] || { echo "la instancia esta apagada"; exit 1; }
ok "backend en $IP"

# ------------------------------------------------- API limpia
log "Creando la API $NOMBRE_API"
VIEJA=$(aws apigateway get-rest-apis --region "$REGION" \
         --query "items[?name=='$NOMBRE_API'].id | [0]" --output text 2>/dev/null || echo None)
if [ "$VIEJA" != "None" ] && [ -n "$VIEJA" ]; then
  aws apigateway delete-rest-api --region "$REGION" --rest-api-id "$VIEJA" >/dev/null
  ok "borrada la version anterior ($VIEJA)"
  sleep 32   # API Gateway limita a 1 operacion de borrado cada 30 s
fi

API=$(aws apigateway create-rest-api --region "$REGION" --name "$NOMBRE_API" \
       --description "Pedidos360 EV3 - entrada unica del frontend, rutas explicitas sin proxy" \
       --endpoint-configuration types=REGIONAL --query id --output text)
ok "creada $API"

RAIZ=$(aws apigateway get-resources --region "$REGION" --rest-api-id "$API" \
        --query "items[?path=='/'].id | [0]" --output text)

# ------------------------------------------------- helpers
crear_recurso(){   # $1 segmento, $2 id del padre -> imprime el id
  aws apigateway create-resource --region "$REGION" --rest-api-id "$API" \
    --parent-id "$2" --path-part "$1" --query id --output text
}

# Los response-parameters van por archivo JSON: pasarlos como texto en la linea
# de comandos hace que el shell se coma las comillas simples del valor literal
# y AWS responde "Invalid mapping expression specified: *".
cat > "$TMP/cors-metodo.json" <<'JSON'
{"method.response.header.Access-Control-Allow-Origin": true}
JSON
cat > "$TMP/cors-integracion.json" <<'JSON'
{"method.response.header.Access-Control-Allow-Origin": "'*'"}
JSON
cat > "$TMP/pre-metodo.json" <<'JSON'
{"method.response.header.Access-Control-Allow-Origin": true,
 "method.response.header.Access-Control-Allow-Headers": true,
 "method.response.header.Access-Control-Allow-Methods": true}
JSON

declare -i FALLOS=0

ruta(){   # $1 id recurso, $2 verbo, $3 url destino, $4 etiqueta
  local rec="$1" verbo="$2" destino="$3" etiqueta="$4" e
  if ! e=$(aws apigateway put-method --region "$REGION" --rest-api-id "$API" \
        --resource-id "$rec" --http-method "$verbo" --authorization-type NONE 2>&1); then
    err "$etiqueta put-method: $(head -c 90 <<< "$e")"; FALLOS+=1; return
  fi
  # type=HTTP (no HTTP_PROXY): el destino queda declarado en la integracion.
  if ! e=$(aws apigateway put-integration --region "$REGION" --rest-api-id "$API" \
        --resource-id "$rec" --http-method "$verbo" --type HTTP \
        --integration-http-method "$verbo" --uri "$destino" \
        --passthrough-behavior WHEN_NO_MATCH --timeout-in-millis 29000 2>&1); then
    err "$etiqueta put-integration: $(head -c 90 <<< "$e")"; FALLOS+=1; return
  fi
  if ! e=$(aws apigateway put-method-response --region "$REGION" --rest-api-id "$API" \
        --resource-id "$rec" --http-method "$verbo" --status-code 200 \
        --response-parameters "file://$TMP_URL/cors-metodo.json" 2>&1); then
    err "$etiqueta method-response: $(head -c 90 <<< "$e")"; FALLOS+=1; return
  fi
  # Sin integration-response por defecto, API Gateway devuelve 500 con
  # "No match for output mapping and no default output mapping configured",
  # aunque el backend haya respondido 200 correctamente.
  if ! e=$(aws apigateway put-integration-response --region "$REGION" --rest-api-id "$API" \
        --resource-id "$rec" --http-method "$verbo" --status-code 200 \
        --response-parameters "file://$TMP_URL/cors-integracion.json" 2>&1); then
    err "$etiqueta integration-response: $(head -c 90 <<< "$e")"; FALLOS+=1; return
  fi
  printf '    %-5s %-26s -> %s\n' "$verbo" "$etiqueta" "${destino#http://}"
}

preflight(){   # $1 id recurso, $2 verbos
  local rec="$1" verbos="$2" e
  cat > "$TMP/pre-int.json" <<JSON
{"method.response.header.Access-Control-Allow-Origin": "'*'",
 "method.response.header.Access-Control-Allow-Headers": "'Content-Type,Authorization'",
 "method.response.header.Access-Control-Allow-Methods": "'$verbos,OPTIONS'"}
JSON
  aws apigateway put-method --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method OPTIONS --authorization-type NONE >/dev/null
  aws apigateway put-integration --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method OPTIONS --type MOCK \
    --request-templates '{"application/json":"{\"statusCode\": 200}"}' >/dev/null
  aws apigateway put-method-response --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method OPTIONS --status-code 200 \
    --response-parameters "file://$TMP_URL/pre-metodo.json" >/dev/null
  if ! e=$(aws apigateway put-integration-response --region "$REGION" --rest-api-id "$API" \
        --resource-id "$rec" --http-method OPTIONS --status-code 200 \
        --response-parameters "file://$TMP_URL/pre-int.json" 2>&1); then
    err "preflight: $(head -c 90 <<< "$e")"; FALLOS+=1
  fi
}

# ------------------------------------------------- el arbol
log "Declarando recursos (uno por uno, sin {proxy+})"

V1=$(crear_recurso v1 "$RAIZ")

# /v1/pagos/{iniciar,commit}
PAGOS=$(crear_recurso pagos "$V1")
R=$(crear_recurso iniciar "$PAGOS"); ruta "$R" POST "http://$IP:8083/api/pagos/iniciar" "/v1/pagos/iniciar"; preflight "$R" POST
R=$(crear_recurso commit  "$PAGOS"); ruta "$R" POST "http://$IP:8083/api/pagos/commit"  "/v1/pagos/commit";  preflight "$R" POST

# /v1/<simples>
R=$(crear_recurso alertas         "$V1"); ruta "$R" GET  "http://$IP:8083/api/alertas"         "/v1/alertas";         preflight "$R" GET
R=$(crear_recurso interacciones   "$V1"); ruta "$R" POST "http://$IP:8084/api/interacciones"   "/v1/interacciones";   preflight "$R" POST
R=$(crear_recurso recomendaciones "$V1"); ruta "$R" GET  "http://$IP:8084/api/recomendaciones" "/v1/recomendaciones"; preflight "$R" GET
R=$(crear_recurso metricas        "$V1"); ruta "$R" GET  "http://$IP:8084/api/metricas"        "/v1/metricas";        preflight "$R" GET

# /v1/admin/<...>
ADMIN=$(crear_recurso admin "$V1")
R=$(crear_recurso topicos         "$ADMIN"); ruta "$R" GET "http://$IP:8085/api/topicos"         "/v1/admin/topicos";         preflight "$R" GET
R=$(crear_recurso cluster         "$ADMIN"); ruta "$R" GET "http://$IP:8085/api/cluster"         "/v1/admin/cluster";         preflight "$R" GET
R=$(crear_recurso consumer-groups "$ADMIN"); ruta "$R" GET "http://$IP:8085/api/consumer-groups" "/v1/admin/consumer-groups"; preflight "$R" GET

# ------------------------------------------------- publicar
log "Publicando el stage '$STAGE'"
aws apigateway create-deployment --region "$REGION" --rest-api-id "$API" \
  --stage-name "$STAGE" --description "contrato v1 $(date -Is)" --query id --output text >/dev/null
URL="https://$API.execute-api.$REGION.amazonaws.com/$STAGE"
ok "publicada en $URL"

for k in API_REST API_URL; do grep -q "^$k=" "$ESTADO" || echo "$k=" >> "$ESTADO"; done
sed -i "s|^API_REST=.*|API_REST=$API|; s|^API_URL=.*|API_URL=$URL|" "$ESTADO"

log "Verificando (espera a que propague)"
sleep 20
declare -i OK_N=0 TOT=0
for r in v1/admin/topicos v1/admin/cluster v1/metricas v1/alertas v1/recomendaciones; do
  TOT+=1
  C=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$URL/$r" 2>/dev/null || echo 000)
  if [ "$C" = "200" ]; then OK_N+=1; printf '    \033[1;32m%s\033[0m  GET /%s\n' "$C" "$r"
  else printf '    \033[1;31m%s\033[0m  GET /%s\n' "$C" "$r"; fi
done

echo
echo "    respuesta real de /v1/admin/topicos:"
curl -s --max-time 30 "$URL/v1/admin/topicos" 2>/dev/null | head -c 300
echo
echo
echo "    rutas OK: $OK_N de $TOT   ·   errores de configuracion: $FALLOS"
echo
echo "    PARA EL FRONTEND, una sola base:"
echo "       API_BASE=$URL"
