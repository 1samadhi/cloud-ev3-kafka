#!/usr/bin/env bash
# =============================================================================
#  08-apigateway-ev3.sh — FASE 6: API Gateway REST delante de los microservicios
#
#  Crea UNA API REST con NUEVE recursos declarados uno por uno. Cada metodo
#  tiene su integracion HTTP explicita hacia el microservicio que corresponde.
#
#  POR QUE ASI Y NO CON {proxy+}:
#  El docente observo textualmente en la evaluacion anterior "No usar Proxy en
#  backend". Un recurso {proxy+} con integracion ANY manda TODO a un unico
#  backend y convierte a API Gateway en un simple reenviador: no se declara que
#  rutas existen, no se puede dar permisos ni limites por ruta, y la consola no
#  documenta nada. Declarar recurso por recurso es mas trabajo de configuracion
#  pero es lo que se pide y lo que permite, por ejemplo, poner autenticacion
#  solo en /pagos o limitar /metricas sin tocar el resto.
#
#  El CORS se resuelve con un metodo OPTIONS por recurso (integracion MOCK),
#  que es como API Gateway responde el preflight del navegador.
#
#  Uso:  ./08-apigateway-ev3.sh
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

NOMBRE_API="pedidos360-ev3"
STAGE="desarrollo"
ORIGEN_CORS="*"       # el front todavia no tiene URL fija; se acota cuando la tenga

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }

IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
[ "$IP" != "None" ] || { echo "la instancia no tiene IP publica (esta apagada?)"; exit 1; }
sed -i "s|^IP=.*|IP=$IP|" "$ESTADO"
ok "backend en $IP"

# --------------------------------------------------------------- la API
log "API REST $NOMBRE_API"
API=$(aws apigateway get-rest-apis --region "$REGION" \
       --query "items[?name=='$NOMBRE_API'].id | [0]" --output text 2>/dev/null || echo None)
if [ "$API" = "None" ] || [ -z "$API" ]; then
  API=$(aws apigateway create-rest-api --region "$REGION" --name "$NOMBRE_API" \
         --description "Pedidos360 EV3: entrada unica del frontend, sin proxy" \
         --endpoint-configuration types=REGIONAL \
         --query id --output text)
  ok "creada $API"
else
  ok "ya existia $API"
fi

RAIZ=$(aws apigateway get-resources --region "$REGION" --rest-api-id "$API" \
        --query "items[?path=='/'].id | [0]" --output text)

# Crea (o reutiliza) un recurso hijo de la raiz y devuelve su id.
recurso(){   # $1 = nombre del segmento, $2 = id del padre
  local nombre="$1" padre="$2" id
  id=$(aws apigateway get-resources --region "$REGION" --rest-api-id "$API" --limit 200 \
        --query "items[?pathPart=='$nombre' && parentId=='$padre'].id | [0]" \
        --output text 2>/dev/null || echo None)
  if [ "$id" = "None" ] || [ -z "$id" ]; then
    id=$(aws apigateway create-resource --region "$REGION" --rest-api-id "$API" \
          --parent-id "$padre" --path-part "$nombre" --query id --output text)
  fi
  echo "$id"
}

# Declara metodo + integracion HTTP (NO proxy) hacia el backend.
metodo(){   # $1 recurso, $2 verbo, $3 url destino, $4 etiqueta
  local rec="$1" verbo="$2" destino="$3" etiqueta="$4"
  aws apigateway put-method --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method "$verbo" --authorization-type NONE \
    >/dev/null 2>&1 || true
  # HTTP, no HTTP_PROXY: la integracion declara explicitamente el destino.
  aws apigateway put-integration --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method "$verbo" \
    --type HTTP --integration-http-method "$verbo" --uri "$destino" \
    --passthrough-behavior WHEN_NO_MATCH \
    >/dev/null 2>&1 || true
  aws apigateway put-method-response --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method "$verbo" --status-code 200 \
    --response-parameters "method.response.header.Access-Control-Allow-Origin=true" \
    >/dev/null 2>&1 || true
  aws apigateway put-integration-response --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method "$verbo" --status-code 200 \
    --response-parameters "method.response.header.Access-Control-Allow-Origin='$ORIGEN_CORS'" \
    >/dev/null 2>&1 || true
  printf '    %-6s %-22s -> %s\n' "$verbo" "$etiqueta" "$destino"
}

# OPTIONS con integracion MOCK: asi responde API Gateway el preflight.
preflight(){   # $1 recurso, $2 verbos permitidos
  local rec="$1" verbos="$2"
  aws apigateway put-method --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method OPTIONS --authorization-type NONE >/dev/null 2>&1 || true
  aws apigateway put-integration --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method OPTIONS --type MOCK \
    --request-templates '{"application/json":"{\"statusCode\": 200}"}' >/dev/null 2>&1 || true
  aws apigateway put-method-response --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method OPTIONS --status-code 200 \
    --response-parameters "method.response.header.Access-Control-Allow-Origin=true,method.response.header.Access-Control-Allow-Headers=true,method.response.header.Access-Control-Allow-Methods=true" \
    >/dev/null 2>&1 || true
  aws apigateway put-integration-response --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method OPTIONS --status-code 200 \
    --response-parameters "method.response.header.Access-Control-Allow-Origin='$ORIGEN_CORS',method.response.header.Access-Control-Allow-Headers='Content-Type,Authorization',method.response.header.Access-Control-Allow-Methods='$verbos,OPTIONS'" \
    >/dev/null 2>&1 || true
}

log "Declarando los recursos uno por uno (sin {proxy+})"

# --- /pagos  (y sus dos hijos)
R_PAGOS=$(recurso pagos "$RAIZ")
R_INI=$(recurso iniciar "$R_PAGOS")
R_COM=$(recurso commit "$R_PAGOS")
metodo "$R_INI" POST "http://$IP:8083/api/pagos/iniciar" "/pagos/iniciar"
preflight "$R_INI" "POST"
metodo "$R_COM" POST "http://$IP:8083/api/pagos/commit" "/pagos/commit"
preflight "$R_COM" "POST"

# --- el resto, hijos directos de la raiz
declare -A RUTAS=(
  [alertas]="GET|8083|/api/alertas"
  [interacciones]="POST|8084|/api/interacciones"
  [recomendaciones]="GET|8084|/api/recomendaciones"
  [metricas]="GET|8084|/api/metricas"
  [topicos]="GET|8085|/api/topicos"
  [consumer-groups]="GET|8085|/api/consumer-groups"
  [cluster]="GET|8085|/api/cluster"
)
for nombre in alertas interacciones recomendaciones metricas topicos consumer-groups cluster; do
  IFS='|' read -r verbo puerto ruta <<< "${RUTAS[$nombre]}"
  [ -n "$ruta" ] || { puerto="8085"; ruta="/api/$nombre"; }
  rid=$(recurso "$nombre" "$RAIZ")
  metodo "$rid" "$verbo" "http://$IP:$puerto$ruta" "/$nombre"
  preflight "$rid" "$verbo"
done

# --------------------------------------------------------------- deploy
log "Publicando en el stage '$STAGE'"
aws apigateway create-deployment --region "$REGION" --rest-api-id "$API" \
  --stage-name "$STAGE" --description "despliegue $(date -Is)" \
  --query 'id' --output text >/dev/null
URL="https://$API.execute-api.$REGION.amazonaws.com/$STAGE"
ok "publicada"

# guardar en el estado para las fases siguientes
grep -q '^API_REST=' "$ESTADO" && sed -i "s|^API_REST=.*|API_REST=$API|" "$ESTADO" \
                               || echo "API_REST=$API" >> "$ESTADO"
grep -q '^API_URL=' "$ESTADO"  && sed -i "s|^API_URL=.*|API_URL=$URL|" "$ESTADO" \
                               || echo "API_URL=$URL" >> "$ESTADO"

log "Verificando a traves de API Gateway"
for r in topicos metricas alertas; do
  printf '    GET /%-16s ' "$r"
  curl -s -o /dev/null -w 'HTTP %{http_code}\n' --max-time 25 "$URL/$r" 2>&1 || echo "sin respuesta"
done
echo
echo "    respuesta real de /topicos:"
curl -s --max-time 25 "$URL/topicos" 2>/dev/null | head -c 300
echo

log "FASE 6 lista"
cat <<EOF
    API Gateway: $URL

    Para el front (tu companero usa ESTO, no la IP):
       GET  $URL/metricas
       GET  $URL/recomendaciones?userId=...
       POST $URL/interacciones
       POST $URL/pagos/iniciar
       GET  $URL/alertas
       GET  $URL/topicos
EOF
