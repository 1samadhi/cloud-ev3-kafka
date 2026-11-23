#!/usr/bin/env bash
# =============================================================================
#  13-reparar-respuestas.sh
#
#  POR QUE EXISTE ESTE SCRIPT
#  El script 12 creo las rutas nuevas pero sin la "respuesta de integracion".
#  Sin ella API Gateway recibe el 200 del backend y no sabe que devolver:
#
#     Execution failed due to configuration error:
#       No match for output mapping and no default output mapping configured
#
#  La causa fue de sintaxis. Con la forma  clave="valor='*'"  la AWS CLI se
#  come las comillas simples y rechaza el mapeo:
#
#     Invalid mapping expression specified: *
#
#  El valor de una cabecera estatica DEBE llegar con sus comillas simples, y
#  la unica forma fiable es pasarlo en un archivo JSON con file://.
#
#  Este script recorre TODAS las rutas de /v1 y a las que les falta la
#  respuesta de integracion se la crea bien.
#
#  Uso:  ./13-reparar-respuestas.sh
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Los dos mapeos, en archivos, que es lo que preserva las comillas.
cat > "$TMP/cors.json" <<'JSON'
{"method.response.header.Access-Control-Allow-Origin": "'*'"}
JSON

cat > "$TMP/preflight.json" <<'JSON'
{
  "method.response.header.Access-Control-Allow-Origin": "'*'",
  "method.response.header.Access-Control-Allow-Methods": "'GET,POST,OPTIONS'",
  "method.response.header.Access-Control-Allow-Headers": "'Content-Type,X-Usuario-Id'"
}
JSON

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
mal(){ printf '    \033[1;31mFALLA\033[0m %s\n' "$*"; }

log "Buscando rutas sin respuesta de integracion"
MAPA=$(aws apigateway get-resources --region "$REGION" --rest-api-id "$API_REST" --limit 300 \
        --query 'items[?resourceMethods].{id:id,path:path,m:resourceMethods}' --output json)

ARREGLADAS=0; YA=0; FALLOS=0

while IFS=$'\t' read -r RID RUTA METODO; do
  [ -z "$RID" ] && continue

  # ¿ya tiene respuesta de integracion?
  TIENE=$(aws apigateway get-integration --region "$REGION" --rest-api-id "$API_REST" \
           --resource-id "$RID" --http-method "$METODO" \
           --query 'integrationResponses."200".statusCode' --output text 2>/dev/null || echo None)

  if [ "$TIENE" = "200" ]; then
    YA=$((YA+1)); continue
  fi

  if [ "$METODO" = "OPTIONS" ]; then
    PARAMS="file://$TMP/preflight.json"
    RESP='{"method.response.header.Access-Control-Allow-Origin":false,"method.response.header.Access-Control-Allow-Methods":false,"method.response.header.Access-Control-Allow-Headers":false}'
  else
    PARAMS="file://$TMP/cors.json"
    RESP='{"method.response.header.Access-Control-Allow-Origin":false}'
  fi

  # La respuesta del metodo primero: declara que cabeceras puede devolver.
  aws apigateway put-method-response --region "$REGION" --rest-api-id "$API_REST" \
    --resource-id "$RID" --http-method "$METODO" --status-code 200 \
    --response-parameters "$RESP" >/dev/null 2>&1 || true

  if E=$(aws apigateway put-integration-response --region "$REGION" --rest-api-id "$API_REST" \
          --resource-id "$RID" --http-method "$METODO" --status-code 200 \
          --response-parameters "$PARAMS" 2>&1); then
    ok "$METODO $RUTA"
    ARREGLADAS=$((ARREGLADAS+1))
  else
    mal "$METODO $RUTA: $(head -c 100 <<< "$E")"
    FALLOS=$((FALLOS+1))
  fi
done < <(python3 - "$MAPA" <<'PY'
import sys, json
for r in json.loads(sys.argv[1]):
    for m in (r.get('m') or {}):
        print(f"{r['id']}\t{r['path']}\t{m}")
PY
)

echo
echo "    arregladas: $ARREGLADAS   ya estaban: $YA   fallaron: $FALLOS"

log "Publicando"
aws apigateway create-deployment --region "$REGION" --rest-api-id "$API_REST" \
  --stage-name desarrollo --description "respuestas de integracion de las rutas nuevas" \
  --query id --output text
echo "    esperando propagacion..."; sleep 35

log "Comprobando las 20 rutas"
BASE="https://$API_REST.execute-api.$REGION.amazonaws.com/desarrollo"
PASA=0; FALLA=0
probar(){
  local m="$1" r="$2"; shift 2
  local cod
  cod=$(timeout 35 curl -s -o /dev/null -w '%{http_code}' -X "$m" "$BASE$r" "$@" 2>/dev/null || echo 000)
  case "$cod" in
    200|201) ok "$m $r -> $cod"; PASA=$((PASA+1)) ;;
    *)       mal "$m $r -> $cod"; FALLA=$((FALLA+1)) ;;
  esac
}
U=(-H "X-Usuario-Id: humo@correo.cl")
probar GET /v1/productos
probar GET /v1/productos/categorias
probar GET /v1/carrito "${U[@]}"
probar GET /v1/pedidos "${U[@]}"
probar GET /v1/notificaciones "${U[@]}"
probar GET /v1/rabbit/cluster
probar GET /v1/rabbit/colas
probar GET /v1/metricas
probar GET /v1/alertas
probar GET /v1/recomendaciones?userId=humo@correo.cl
probar GET /v1/admin/topicos
probar GET /v1/admin/cluster
probar GET /v1/admin/consumer-groups

echo
echo "    $PASA bien, $FALLA con problema"
echo "    API_BASE = $BASE"
