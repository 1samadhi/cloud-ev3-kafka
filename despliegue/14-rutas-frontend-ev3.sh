#!/usr/bin/env bash
# =============================================================================
#  14-rutas-frontend-ev3.sh — las rutas que el NAVEGADOR necesita
#
#  QUE PROBLEMA RESUELVE
#  El 10 y el 12 declararon el contrato /v1 y se verifico con curl. Pero los dos
#  frontends no pueden usarlo, por tres razones que solo aparecen desde un
#  navegador:
#
#   1. Las rutas. El frontend construye  <base>/api/<recurso>  (es la misma ruta
#      que exponen los servicios). Con base=<gateway> y rutas /v1/... el
#      resultado seria /v1/.../api/... y da 404. Aqui se declaran las rutas
#      /api/* , que son las que el frontend ya pide: el gateway queda
#      TRANSPARENTE y lo unico que cambia en el frontend es el host.
#
#   2. Las query strings y los parametros de ruta. En una integracion HTTP
#      no-proxy, API Gateway NO reenvia nada que no este declarado. Verificado:
#        /v1/recomendaciones?userId=x  ->  "Required request parameter 'userId'
#        for method parameter type String is not present"
#      Por eso cada ruta declara aqui sus parametros, uno por uno.
#
#   3. La cabecera X-Usuario-Id. La tienda la manda al abrir un producto y ESA
#      es la señal que publica en interacciones-user (de ahi salen las
#      recomendaciones y el dashboard del +3). Sin declararla se descarta, y el
#      fallo es SILENCIOSO: la pagina se ve bien y la telemetria deja de recibir.
#
#  CORS: a diferencia del 10/13, aqui NO se devuelve '*'. Se reenvia la cabecera
#  Origin al servicio y se devuelve tal cual la respuesta CORS que da Spring, que
#  ya valida el origen contra su lista explicita (CORS_ORIGENES). Asi el
#  comportamiento por el gateway es identico al directo, no hay comodin, y el
#  preflight OPTIONS lo contesta el propio servicio en vez de un MOCK.
#
#  NO PASA POR EL GATEWAY, a proposito:
#   - /api/notificaciones/stream : es SSE (EventSource) y API Gateway REST
#     bufferea las respuestas, asi que un stream no atraviesa. Queda directo.
#   - /ws/metricas : WebSocket; necesita una API de tipo WebSocket (es el diseño
#     del +3), no esta REST.
#   - /api/pagos/commit : lo abre Transbank redirigiendo el NAVEGADOR, no el
#     frontend; va directo por PAGOS_RETURN_URL.
#
#  Las rutas estan ORDENADAS POR IMPORTANCIA para la demo: si el script se
#  cortara a mitad, lo que queda arriba es lo que se muestra primero.
#
#  Requiere: .ev3-estado.env con API_REST (lo deja el 10) e IP.
#  Es idempotente: reutiliza los recursos que ya existan.
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO — corre primero 01 y 10"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
: "${REGION:?}" "${IP:?}" "${API_REST:?falta API_REST; corre 10-apigateway-v1.sh}"
API="$API_REST"
STAGE="${STAGE:-desarrollo}"
export AWS_PAGER=""

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
# El AWS CLI de Windows es nativo y no entiende rutas MSYS (/tmp/...): los
# file:// fallan con "Unable to load paramfile". En Linux cygpath no existe.
TMP_URL=$(cygpath -m "$TMP" 2>/dev/null || echo "$TMP")

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
err(){ printf '    \033[1;31mFALLA\033[0m %s\n' "$*"; }

# El AWS CLI de Windows escribe CRLF. Un id con \r al final rompe la firma
# SigV4 (el id viaja en la URI de la peticion) y AWS responde
#   UnrecognizedClientException: The security token included ... is invalid
# que manda a buscar un problema de credenciales inexistente. Verificado:
#   --parent-id "abc123\r" -> UnrecognizedClientException
#   --parent-id "abc123"   -> crea el recurso
# Por eso TODO lo que se lee del CLI pasa por aqui.
sincr(){ tr -d '\r'; }

declare -i FALLOS=0
declare -A IDS            # ruta -> id de recurso, para no recrear

RAIZ=$(aws apigateway get-resources --region "$REGION" --rest-api-id "$API" \
        --limit 500 --query "items[?path=='/'].id | [0]" --output text | sincr)
IDS["/"]="$RAIZ"

# Precarga los recursos que ya existen: hace el script re-ejecutable.
while read -r RUTA ID; do
  [ -n "${RUTA:-}" ] && IDS["$RUTA"]="$ID"
done < <(aws apigateway get-resources --region "$REGION" --rest-api-id "$API" \
          --limit 500 --query 'items[].[path,id]' --output text | sincr)

# ---------------------------------------------------------------- recursos
# Camina la ruta creando los segmentos que falten y deja el id en REC_ID.
#
# NO devuelve el id por stdout a proposito: llamarla con $(...) la ejecuta en
# una SUBSHELL y los ids que guarda en IDS se pierden al volver, asi que la
# siguiente ruta vuelve a intentar crear /api (conflicto) y termina pasando
# --parent-id vacio. AWS responde a eso con errores que no tienen nada que ver
# (UnrecognizedClientException, UnknownOperation) y se pierde media hora
# buscando un problema de credenciales que no existe.
REC_ID=""
recurso(){   # $1 = /api/productos/{id}/stock
  local ruta="$1" acum="" seg padre id e
  for seg in $(echo "$ruta" | tr '/' ' '); do
    padre="${IDS[${acum:-/}]}"
    acum="$acum/$seg"
    if [ -z "${IDS[$acum]:-}" ]; then
      if ! id=$(aws apigateway create-resource --region "$REGION" --rest-api-id "$API" \
                 --parent-id "$padre" --path-part "$seg" --query id --output text 2>&1 | sincr); then
        # Si ya existia (carrera o corrida anterior a medias), se relee.
        e="$id"
        id=$(aws apigateway get-resources --region "$REGION" --rest-api-id "$API" \
              --limit 500 --query "items[?path=='$acum'].id | [0]" --output text 2>/dev/null | sincr || echo None)
        if [ "$id" = "None" ] || [ -z "$id" ]; then
          err "no pude crear ni encontrar $acum: $(head -c 100 <<< "$e")"
          FALLOS+=1; REC_ID=""; return 1
        fi
      fi
      IDS["$acum"]="$id"
    fi
  done
  REC_ID="${IDS[$ruta]}"
}

# ---------------------------------------------------------------- parametros
# Construye los dos JSON de parametros a partir de la ruta y la lista de query.
# Siempre se incluyen Origin (para que Spring decida el CORS) y X-Usuario-Id.
params(){   # $1 ruta, $2 "q1,q2" (puede ir vacio)
  local ruta="$1" queries="${2:-}" m i p q
  m='"method.request.header.Origin": false, "method.request.header.X-Usuario-Id": false'
  i='"integration.request.header.Origin": "method.request.header.Origin", "integration.request.header.X-Usuario-Id": "method.request.header.X-Usuario-Id"'
  # parametros de ruta: cada {algo} del camino
  for p in $(grep -oE '\{[a-zA-Z]+\}' <<< "$ruta" | tr -d '{}'); do
    m="$m, \"method.request.path.$p\": true"
    i="$i, \"integration.request.path.$p\": \"method.request.path.$p\""
  done
  # query strings declaradas (opcionales: el servicio valida si son obligatorias)
  if [ -n "$queries" ]; then
    for q in $(tr ',' ' ' <<< "$queries"); do
      m="$m, \"method.request.querystring.$q\": false"
      i="$i, \"integration.request.querystring.$q\": \"method.request.querystring.$q\""
    done
  fi
  printf '{%s}' "$m" > "$TMP/pm.json"
  printf '{%s}' "$i" > "$TMP/pi.json"
}

# CORS: se devuelve lo que responda el servicio, no un valor fijo.
cat > "$TMP/resp-metodo.json" <<'JSON'
{"method.response.header.Access-Control-Allow-Origin": true,
 "method.response.header.Access-Control-Allow-Credentials": true}
JSON
cat > "$TMP/resp-integracion.json" <<'JSON'
{"method.response.header.Access-Control-Allow-Origin": "integration.response.header.Access-Control-Allow-Origin",
 "method.response.header.Access-Control-Allow-Credentials": "integration.response.header.Access-Control-Allow-Credentials"}
JSON
cat > "$TMP/pre-metodo.json" <<'JSON'
{"method.response.header.Access-Control-Allow-Origin": true,
 "method.response.header.Access-Control-Allow-Headers": true,
 "method.response.header.Access-Control-Allow-Methods": true,
 "method.response.header.Access-Control-Max-Age": true}
JSON
cat > "$TMP/pre-integracion.json" <<'JSON'
{"method.response.header.Access-Control-Allow-Origin": "integration.response.header.Access-Control-Allow-Origin",
 "method.response.header.Access-Control-Allow-Headers": "integration.response.header.Access-Control-Allow-Headers",
 "method.response.header.Access-Control-Allow-Methods": "integration.response.header.Access-Control-Allow-Methods",
 "method.response.header.Access-Control-Max-Age": "integration.response.header.Access-Control-Max-Age"}
JSON
# El preflight viaja al servicio con las tres cabeceras que lo definen.
cat > "$TMP/pre-pm.json" <<'JSON'
{"method.request.header.Origin": false,
 "method.request.header.Access-Control-Request-Method": false,
 "method.request.header.Access-Control-Request-Headers": false}
JSON
cat > "$TMP/pre-pi.json" <<'JSON'
{"integration.request.header.Origin": "method.request.header.Origin",
 "integration.request.header.Access-Control-Request-Method": "method.request.header.Access-Control-Request-Method",
 "integration.request.header.Access-Control-Request-Headers": "method.request.header.Access-Control-Request-Headers"}
JSON

# ---------------------------------------------------------------- metodos
metodo(){   # $1 id, $2 verbo, $3 uri, $4 etiqueta
  local rec="$1" verbo="$2" uri="$3" et="$4" e
  if ! e=$(aws apigateway put-method --region "$REGION" --rest-api-id "$API" \
        --resource-id "$rec" --http-method "$verbo" --authorization-type NONE \
        --request-parameters "file://$TMP_URL/pm.json" 2>&1); then
    # Un metodo que ya existe no es un fallo: el script tiene que poder
    # re-ejecutarse para agregar un verbo a una ruta ya publicada. put-method no
    # acepta sobrescribir, pero la integracion y las respuestas de abajo si, y
    # son las que importan.
    if grep -q "ConflictException" <<< "$e"; then
      :
    else
      err "$et $verbo put-method: $(head -c 80 <<< "$e")"; FALLOS+=1; return
    fi
  fi
  # type=HTTP, NO HTTP_PROXY: cada destino queda declarado.
  if ! e=$(aws apigateway put-integration --region "$REGION" --rest-api-id "$API" \
        --resource-id "$rec" --http-method "$verbo" --type HTTP \
        --integration-http-method "$verbo" --uri "$uri" \
        --request-parameters "file://$TMP_URL/pi.json" \
        --passthrough-behavior WHEN_NO_MATCH --timeout-in-millis 29000 2>&1); then
    err "$et $verbo put-integration: $(head -c 80 <<< "$e")"; FALLOS+=1; return; fi
  aws apigateway put-method-response --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method "$verbo" --status-code 200 \
    --response-parameters "file://$TMP_URL/resp-metodo.json" >/dev/null 2>&1 || true
  # Sin integration-response, API Gateway devuelve 500 ("No match for output
  # mapping") aunque el backend haya respondido 200.
  if ! e=$(aws apigateway put-integration-response --region "$REGION" --rest-api-id "$API" \
        --resource-id "$rec" --http-method "$verbo" --status-code 200 \
        --response-parameters "file://$TMP_URL/resp-integracion.json" 2>&1); then
    err "$et $verbo integration-response: $(head -c 80 <<< "$e")"; FALLOS+=1; return; fi
}

preflight(){   # $1 id, $2 uri, $3 ruta
  local rec="$1" uri="$2" ruta="${3:-}" p pm pi
  # Los parametros de RUTA tambien hacen falta en el preflight: la URI de la
  # integracion lleva {id} y, si no se declara de donde sale, API Gateway no
  # puede armarla y responde 500 al OPTIONS. El navegador entonces ni manda la
  # peticion real y el frontend reporta "el servicio no responde", que manda a
  # buscar un microservicio caido que esta perfectamente sano.
  # Solo se ve con una cabecera que dispare preflight (Authorization,
  # X-Usuario-Id) sobre una ruta con parametro: /api/productos/{id} falla y
  # /api/productos responde bien.
  local pmr pir
  pmr='"method.request.header.Origin": false, "method.request.header.Access-Control-Request-Method": false, "method.request.header.Access-Control-Request-Headers": false'
  pir='"integration.request.header.Origin": "method.request.header.Origin", "integration.request.header.Access-Control-Request-Method": "method.request.header.Access-Control-Request-Method", "integration.request.header.Access-Control-Request-Headers": "method.request.header.Access-Control-Request-Headers"'
  for p in $(grep -oE '\{[a-zA-Z]+\}' <<< "$ruta" | tr -d '{}'); do
    pmr="$pmr, \"method.request.path.$p\": true"
    pir="$pir, \"integration.request.path.$p\": \"method.request.path.$p\""
  done
  printf '{%s}' "$pmr" > "$TMP/pre-pm.json"
  printf '{%s}' "$pir" > "$TMP/pre-pi.json"

  aws apigateway put-method --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method OPTIONS --authorization-type NONE \
    --request-parameters "file://$TMP_URL/pre-pm.json" >/dev/null 2>&1 || true
  # Un OPTIONS que ya existia conserva sus parametros viejos y put-method no
  # puede sobrescribirlo (ConflictException). Se agregan uno por uno con
  # update-*: `op=add`, no `replace`, porque el parametro todavia NO existe y un
  # replace sobre algo ausente no hace nada y no da error.
  for p in $(grep -oE '\{[a-zA-Z]+\}' <<< "$ruta" | tr -d '{}'); do
    aws apigateway update-method --region "$REGION" --rest-api-id "$API" \
      --resource-id "$rec" --http-method OPTIONS \
      --patch-operations "op=add,path=/requestParameters/method.request.path.$p,value=true" >/dev/null 2>&1 || true
    aws apigateway update-integration --region "$REGION" --rest-api-id "$API" \
      --resource-id "$rec" --http-method OPTIONS \
      --patch-operations "op=add,path=/requestParameters/integration.request.path.$p,value=method.request.path.$p" >/dev/null 2>&1 || true
  done
  aws apigateway put-integration --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method OPTIONS --type HTTP \
    --integration-http-method OPTIONS --uri "$uri" \
    --request-parameters "file://$TMP_URL/pre-pi.json" \
    --passthrough-behavior WHEN_NO_MATCH --timeout-in-millis 29000 >/dev/null 2>&1 || true
  aws apigateway put-method-response --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method OPTIONS --status-code 200 \
    --response-parameters "file://$TMP_URL/pre-metodo.json" >/dev/null 2>&1 || true
  aws apigateway put-integration-response --region "$REGION" --rest-api-id "$API" \
    --resource-id "$rec" --http-method OPTIONS --status-code 200 \
    --response-parameters "file://$TMP_URL/pre-integracion.json" >/dev/null 2>&1 || true
}

# ---------------------------------------------------------------- las rutas
# puerto | verbos | ruta | query strings | ruta en el servicio (opcional)
# El quinto campo existe solo para /salud/*: la ruta publica no coincide con la
# del servicio (/actuator/health). En el resto, el gateway es transparente y la
# ruta publica ES la del servicio, que es lo que hace facil de defender el
# diseño y de depurar un fallo.
RUTAS=(
  # --- lo que se muestra primero en la demo: catalogo y navegacion
  "8086|GET|/api/productos|categoria,q,orden"
  "8086|GET|/api/productos/categorias|"
  "8086|GET|/api/productos/{id}|"
  "8084|GET|/api/recomendaciones|userId"
  "8084|GET|/api/metricas|"
  "8084|POST|/api/interacciones|"
  # --- carrito y pedido
  # Los verbos salen de tienda/src/lib/api.ts, uno por uno: una ruta publicada
  # con el verbo equivocado responde 403 "Missing Authentication Token" igual
  # que una ruta inexistente, y en el navegador se ve como un fallo de red.
  # Verificado el 26-11: por el gateway daban 403 y directo funcionaban
  # PATCH /items/{id} (cambiar cantidad), DELETE /carrito (vaciar),
  # DELETE /cupon (quitar), PUT /envio (paso del checkout).
  "8087|GET,DELETE|/api/carrito|usuarioId"
  "8087|POST|/api/carrito/items|"
  "8087|DELETE,PATCH|/api/carrito/items/{productoId}|usuarioId"
  "8087|POST,DELETE|/api/carrito/cupon|"
  "8087|POST,PUT|/api/carrito/envio|"
  "8087|GET|/api/carrito/sucursales|"
  "8087|GET,POST|/api/pedidos|usuarioId"
  "8087|GET|/api/pedidos/{id}|"
  # --- pago y antifraude
  "8083|POST|/api/pagos/iniciar|"
  "8083|GET|/api/alertas|"
  # --- notificaciones (el stream SSE va directo, ver cabecera)
  "8088|GET|/api/notificaciones|usuarioId"
  "8088|PATCH|/api/notificaciones/{id}/leida|"
  "8088|POST|/api/notificaciones/leidas|usuarioId"
  # --- administracion de Kafka
  "8085|GET|/api/topicos|"
  "8085|GET|/api/topicos/{nombre}|"
  "8085|GET|/api/cluster|"
  "8085|GET|/api/consumer-groups|"
  "8085|GET|/api/consumer-groups/{id}/lag|"
  # --- administracion de RabbitMQ y panel admin de la tienda
  "8089|GET|/api/rabbit/cluster|"
  "8089|GET|/api/rabbit/colas|"
  "8086|GET,PUT|/api/productos/{id}/stock|"
  "8086|GET|/api/productos/admin/stock|"
  "8087|GET|/api/admin/resumen|"
  "8087|GET|/api/admin/pedidos|"
  # --- salud de los cinco servicios que consume la tienda
  # Con una sola base, `${base}/actuator/health` no distingue de quien es la
  # salud, y el gateway no publica /actuator: responde 403 SIN cabeceras CORS, y
  # el navegador cuenta eso como fallo de red. Resultado visible: la insignia de
  # la tienda decia "modo demo · sin backend" con los cinco servicios arriba.
  # Cinco rutas explicitas, cada una al /actuator/health de su puerto.
  "8086|GET|/salud/productos||/actuator/health"
  "8087|GET|/salud/carrito||/actuator/health"
  "8083|GET|/salud/pagos||/actuator/health"
  "8084|GET|/salud/recomendaciones||/actuator/health"
  "8088|GET|/salud/notificaciones||/actuator/health"
)

log "Declarando ${#RUTAS[@]} rutas /api/* en la API $API (IP $IP)"
# SOLO_RUTA permite re-correr una parte sin volver a tocar las 30 rutas: cada
# escritura del management API va a ~1 por segundo y la corrida completa son
# varios minutos. Ejemplo: SOLO_RUTA='/api/carrito' ./14-rutas-frontend-ev3.sh
for ENTRADA in "${RUTAS[@]}"; do
  IFS='|' read -r PUERTO VERBOS RUTA QUERIES DESTINO <<< "$ENTRADA"
  if [ -n "${SOLO_RUTA:-}" ] && [[ "$RUTA" != *"$SOLO_RUTA"* ]]; then continue; fi
  if ! recurso "$RUTA"; then continue; fi
  REC="$REC_ID"
  URI="http://$IP:$PUERTO${DESTINO:-$RUTA}"
  params "$RUTA" "$QUERIES"
  for V in $(tr ',' ' ' <<< "$VERBOS"); do
    metodo "$REC" "$V" "$URI" "$RUTA"
  done
  preflight "$REC" "$URI" "$RUTA"
  printf '    %-7s %-34s -> :%s\n' "$VERBOS" "$RUTA" "$PUERTO"
done

log "Publicando en el stage '$STAGE'"
aws apigateway create-deployment --region "$REGION" --rest-api-id "$API" \
  --stage-name "$STAGE" --description "rutas /api del frontend $(date -Is)" \
  --query id --output text | sed 's/^/    despliegue /'

URL="https://$API.execute-api.$REGION.amazonaws.com/$STAGE"
grep -q '^API_URL=' "$ESTADO" && sed -i "s|^API_URL=.*|API_URL=$URL|" "$ESTADO" \
                              || echo "API_URL=$URL" >> "$ESTADO"

log "Resultado"
if [ "$FALLOS" -gt 0 ]; then
  err "$FALLOS operaciones fallaron (revisa arriba)"
else
  ok "las ${#RUTAS[@]} rutas quedaron declaradas"
fi
cat <<EOF

    Para el frontend, una sola base:
       API_BASE=$URL

    Comprobacion (espera ~1 min de propagacion del stage):
       curl -s "$URL/api/productos/categorias"
       curl -s "$URL/api/recomendaciones?userId=prueba"     # la query debe llegar
       curl -s -D - -o /dev/null -H "Origin: http://$IP:8091" "$URL/api/productos"
EOF
