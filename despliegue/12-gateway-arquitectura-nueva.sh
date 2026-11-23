#!/usr/bin/env bash
# =============================================================================
#  12-gateway-arquitectura-nueva.sh
#
#  Despues de pasar de 3 a 7 microservicios faltan dos cosas:
#
#   1. ABRIR LOS PUERTOS NUEVOS en el grupo de seguridad. Sin esto el API
#      Gateway no alcanza los servicios y las rutas responden 504 sin decir
#      por que, porque la integracion HTTP sale a la IP publica.
#
#   2. AGREGAR LAS RUTAS NUEVAS al API Gateway, una por una y sin {proxy+},
#      que es lo que exige la correccion.
#
#  Mapa de puertos de la arquitectura nueva:
#      ms-pago            8083   /api/pagos      /api/alertas
#      ms-recomendaciones 8084   /api/recomendaciones /api/interacciones /api/metricas
#      ms-kafka-admin     8085   /api/admin
#      ms-productos       8086   /api/productos
#      ms-carrito         8087   /api/carrito    /api/pedidos
#      ms-notificaciones  8088   /api/notificaciones
#      ms-rabbit-admin    8089   /api/rabbit
#      consola 8090   tienda 8091
#
#  Uso:  ./12-gateway-arquitectura-nueva.sh
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
nota(){ printf '    \033[1;33m··\033[0m %s\n' "$*"; }

# ---------------------------------------------------------------- 1. puertos
log "Abriendo los puertos de los servicios nuevos"
for P in 8086 8087 8088 8089 8091 15672; do
  if aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG" \
       --protocol tcp --port "$P" --cidr 0.0.0.0/0 >/dev/null 2>&1; then
    ok "abierto $P"
  else
    nota "$P ya estaba abierto"
  fi
done

# ------------------------------------------------------------- 2. gateway
# Cada entrada:  ruta_en_el_gateway | metodos | puerto | ruta_en_el_backend
# El orden importa: los padres se crean antes que los hijos.
RUTAS=(
  "productos|GET|8086|/api/productos"
  "productos/categorias|GET|8086|/api/productos/categorias"
  "carrito|GET|8087|/api/carrito"
  "carrito/items|POST|8087|/api/carrito/items"
  "carrito/cupon|POST|8087|/api/carrito/cupon"
  "carrito/envio|POST|8087|/api/carrito/envio"
  "pedidos|GET,POST|8087|/api/pedidos"
  "notificaciones|GET|8088|/api/notificaciones"
  "rabbit/cluster|GET|8089|/api/rabbit/cluster"
  "rabbit/colas|GET|8089|/api/rabbit/colas"
)

RAIZ=$(aws apigateway get-resources --region "$REGION" --rest-api-id "$API_REST" --limit 300 \
        --query "items[?path=='/'].id | [0]" --output text)
V1=$(aws apigateway get-resources --region "$REGION" --rest-api-id "$API_REST" --limit 300 \
      --query "items[?path=='/v1'].id | [0]" --output text)
[ "$V1" = "None" ] && { echo "no existe /v1"; exit 1; }
ok "api=$API_REST  /v1=$V1"

# Crea el recurso si no existe y devuelve su id.
recurso_id(){
  local padre="$1" parte="$2" ruta="$3" id
  id=$(aws apigateway get-resources --region "$REGION" --rest-api-id "$API_REST" --limit 300 \
        --query "items[?path=='$ruta'].id | [0]" --output text 2>/dev/null)
  if [ "$id" = "None" ] || [ -z "$id" ]; then
    id=$(aws apigateway create-resource --region "$REGION" --rest-api-id "$API_REST" \
          --parent-id "$padre" --path-part "$parte" --query id --output text)
  fi
  echo "$id"
}

log "Creando las rutas nuevas"
for ENTRADA in "${RUTAS[@]}"; do
  IFS='|' read -r RUTA METODOS PUERTO DESTINO <<< "$ENTRADA"

  # Resuelve la cadena de recursos: carrito/items => /v1/carrito y luego /v1/carrito/items
  PADRE="$V1"; ACUM="/v1"
  IFS='/' read -ra PARTES <<< "$RUTA"
  for PARTE in "${PARTES[@]}"; do
    ACUM="$ACUM/$PARTE"
    PADRE=$(recurso_id "$PADRE" "$PARTE" "$ACUM")
  done
  RID="$PADRE"

  IFS=',' read -ra MS <<< "$METODOS"
  for M in "${MS[@]}"; do
    URI="http://$IP:$PUERTO$DESTINO"

    aws apigateway put-method --region "$REGION" --rest-api-id "$API_REST" \
      --resource-id "$RID" --http-method "$M" --authorization-type NONE \
      --no-api-key-required >/dev/null 2>&1 || true

    aws apigateway put-integration --region "$REGION" --rest-api-id "$API_REST" \
      --resource-id "$RID" --http-method "$M" \
      --type HTTP --integration-http-method "$M" --uri "$URI" \
      --connection-type INTERNET --timeout-in-millis 29000 >/dev/null 2>&1 || true

    aws apigateway put-method-response --region "$REGION" --rest-api-id "$API_REST" \
      --resource-id "$RID" --http-method "$M" --status-code 200 \
      --response-parameters "method.response.header.Access-Control-Allow-Origin=false" \
      >/dev/null 2>&1 || true

    aws apigateway put-integration-response --region "$REGION" --rest-api-id "$API_REST" \
      --resource-id "$RID" --http-method "$M" --status-code 200 \
      --response-parameters "method.response.header.Access-Control-Allow-Origin='*'" \
      >/dev/null 2>&1 || true

    # La cabecera que identifica al usuario viaja en los servicios nuevos.
    if [ "$M" != "GET" ]; then
      aws apigateway update-method --region "$REGION" --rest-api-id "$API_REST" \
        --resource-id "$RID" --http-method "$M" \
        --patch-operations op=add,path=/requestParameters/method.request.header.X-Usuario-Id,value=false \
        >/dev/null 2>&1 || true
      aws apigateway update-integration --region "$REGION" --rest-api-id "$API_REST" \
        --resource-id "$RID" --http-method "$M" \
        --patch-operations op=add,path=/requestParameters/integration.request.header.X-Usuario-Id,value=method.request.header.X-Usuario-Id \
        >/dev/null 2>&1 || true
    fi

    ok "$M /v1/$RUTA  ->  :$PUERTO$DESTINO"
  done

  # OPTIONS para el preflight del navegador (MOCK, no toca el backend)
  aws apigateway put-method --region "$REGION" --rest-api-id "$API_REST" \
    --resource-id "$RID" --http-method OPTIONS --authorization-type NONE >/dev/null 2>&1 || true
  aws apigateway put-integration --region "$REGION" --rest-api-id "$API_REST" \
    --resource-id "$RID" --http-method OPTIONS --type MOCK \
    --request-templates '{"application/json":"{\"statusCode\":200}"}' >/dev/null 2>&1 || true
  aws apigateway put-method-response --region "$REGION" --rest-api-id "$API_REST" \
    --resource-id "$RID" --http-method OPTIONS --status-code 200 \
    --response-parameters "method.response.header.Access-Control-Allow-Origin=false,method.response.header.Access-Control-Allow-Methods=false,method.response.header.Access-Control-Allow-Headers=false" \
    >/dev/null 2>&1 || true
  aws apigateway put-integration-response --region "$REGION" --rest-api-id "$API_REST" \
    --resource-id "$RID" --http-method OPTIONS --status-code 200 \
    --response-parameters "method.response.header.Access-Control-Allow-Origin='*',method.response.header.Access-Control-Allow-Methods='GET,POST,OPTIONS',method.response.header.Access-Control-Allow-Headers='Content-Type,X-Usuario-Id'" \
    >/dev/null 2>&1 || true
done

log "Publicando"
DEP=$(aws apigateway create-deployment --region "$REGION" --rest-api-id "$API_REST" \
       --stage-name desarrollo --description "rutas de la arquitectura de 7 microservicios" \
       --query id --output text)
ok "deployment $DEP"
echo "    esperando propagacion..."; sleep 30

log "Comprobando"
BASE="https://$API_REST.execute-api.$REGION.amazonaws.com/desarrollo"
PASA=0; FALLA=0
probar(){
  local m="$1" r="$2" extra="${3:-}" cod
  cod=$(eval timeout 35 curl -s -o /dev/null -w '%{http_code}' -X "$m" "'$BASE$r'" $extra 2>/dev/null || echo 000)
  if [ "$cod" = "200" ] || [ "$cod" = "201" ]; then
    ok "$m $r -> $cod"; PASA=$((PASA+1))
  else
    nota "$m $r -> $cod"; FALLA=$((FALLA+1))
  fi
}
probar GET /v1/productos
probar GET /v1/productos/categorias
probar GET /v1/carrito "-H 'X-Usuario-Id: prueba@correo.cl'"
probar GET /v1/notificaciones
probar GET /v1/rabbit/cluster
probar GET /v1/metricas
probar GET /v1/admin/topicos

echo
echo "    $PASA bien, $FALLA con problema"
echo "    API_BASE = $BASE"
grep -q '^API_URL=' "$ESTADO" && sed -i "s|^API_URL=.*|API_URL=$BASE|" "$ESTADO" || echo "API_URL=$BASE" >> "$ESTADO"
