#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 15-respuestas-de-error.sh
#
# Problema que resuelve
#   Los metodos de /api/* publicados por el 14 declaran UNA sola respuesta de
#   integracion: la 200 (el default). API Gateway REST con integracion HTTP
#   no-proxy aplica ese default a CUALQUIER codigo que devuelva el servicio, asi
#   que un 404, un 429 o un 500 del backend llegan al navegador como 200 con el
#   cuerpo del error. El cuerpo viaja bien y por eso curl "se ve bien", pero la
#   tienda decide por el codigo: api.ts solo lanza con !res.ok y checkout.ts abre
#   la pantalla de antifraude con status === 429. Resultado: el 4.o pago con la
#   misma tarjeta se mostraba como "Respuesta inesperada (HTTP 200)" en vez del
#   bloqueo, y un producto inexistente dejaba de decir que no existe.
#
#   Verificado antes del arreglo (gateway vih5lem02j, 26-11):
#     pedido inexistente   directo 404 -> gateway 200
#     GET a endpoint POST  directo 405 -> gateway 200
#     antifraude, 4 pagos  directo [200,200,200,429] -> gateway [200,200,200,200]
#
# Que hace
#   Por cada metodo de /api/* (OPTIONS no, lo contesta el servicio) declara la
#   method response y la integration response de cada codigo de error, con
#   selectionPattern exacto y REPLICANDO los mapeos CORS del 200: si una
#   respuesta de error no lleva Access-Control-Allow-Origin, el navegador la
#   bloquea y el frontend ve un fallo de red en vez del error real.
#
# Uso
#   ./15-respuestas-de-error.sh                 # todas las rutas /api/*
#   ./15-respuestas-de-error.sh /api/pagos      # solo las que empiezan asi
#   SIN_DESPLIEGUE=1 ./15-respuestas-de-error.sh  # no republica el stage
#
# Notas de entorno
#   - Corre igual en Linux y en Git Bash de Windows. Todo lo que se lee del AWS
#     CLI pasa por `tr -d '\r'`: un \r en un id rompe la firma SigV4 y AWS
#     responde UnrecognizedClientException, que parece un problema de
#     credenciales y no lo es.
#   - Las escrituras del Management API de API Gateway van a ~1 por segundo:
#     el script reintenta ante TooManyRequestsException.
# ---------------------------------------------------------------------------
set -uo pipefail

REGION="${REGION:-us-east-1}"
API_ID="${API_ID:-vih5lem02j}"
STAGE="${STAGE:-desarrollo}"
FILTRO="${1:-/api}"

CORS_METODO='{"method.response.header.Access-Control-Allow-Origin":true,"method.response.header.Access-Control-Allow-Credentials":true}'
CORS_INTEGRACION='{"method.response.header.Access-Control-Allow-Origin":"integration.response.header.Access-Control-Allow-Origin","method.response.header.Access-Control-Allow-Credentials":"integration.response.header.Access-Control-Allow-Credentials"}'

# Codigos que el frontend distingue en todas las rutas.
#   400 datos invalidos · 404 no encontrado · 500 error del servicio
#   503 dependencia caida (Kafka no confirmo; la tienda lo llama "no disponible")
GENERICOS="400 404 500 503"

# Codigos propios de una ruta. 429 es el antifraude, 402 el rechazo de la
# pasarela simulada, 409 el conflicto de stock o de pedido ya pagado.
codigos_de() {
  case "$1" in
    /api/pagos/iniciar)          echo "$GENERICOS 402 429 502" ;;
    /api/pedidos)                echo "$GENERICOS 409" ;;
    /api/carrito/items*)         echo "$GENERICOS 409" ;;
    /api/carrito/cupon)          echo "$GENERICOS 409" ;;
    *)                           echo "$GENERICOS" ;;
  esac
}

aws_api() {  # aws_api <subcomando...> ; reintenta el rate limit del management API
  local intento=0 salida codigo
  while :; do
    salida=$(aws apigateway "$@" --region "$REGION" --output json 2>&1 | tr -d '\r')
    codigo=$?
    if [ $codigo -eq 0 ]; then printf '%s' "$salida"; return 0; fi
    if printf '%s' "$salida" | grep -q "TooManyRequestsException" && [ $intento -lt 8 ]; then
      intento=$((intento + 1)); sleep $((intento * 2)); continue
    fi
    # Una respuesta que ya existe no es un fallo: el script tiene que poder
    # re-correrse sobre rutas ya tratadas sin ensuciar el resumen.
    if printf '%s' "$salida" | grep -q "ConflictException"; then return 0; fi
    printf '%s' "$salida" >&2; return 1
  done
}

echo "API $API_ID · region $REGION · stage $STAGE · filtro $FILTRO"
echo

RECURSOS=$(aws_api get-resources --rest-api-id "$API_ID" --limit 500) || exit 1

# path<TAB>id<TAB>metodo, una linea por metodo, OPTIONS excluido.
LINEAS=$(printf '%s' "$RECURSOS" | python -c "
import json, sys
d = json.load(sys.stdin)
filtro = sys.argv[1]
for it in sorted(d['items'], key=lambda x: x['path']):
    if not it['path'].startswith(filtro):
        continue
    for m in sorted((it.get('resourceMethods') or {}).keys()):
        if m != 'OPTIONS':
            print('%s\t%s\t%s' % (it['path'], it['id'], m))
" "$FILTRO" | tr -d '\r')

total_metodos=$(printf '%s\n' "$LINEAS" | grep -c . )
echo "Metodos a tratar: $total_metodos"
echo

aplicados=0; fallidos=0; inicio=$(date +%s)
while IFS=$'\t' read -r ruta rid metodo; do
  [ -z "${ruta:-}" ] && continue
  codigos=$(codigos_de "$ruta")
  printf '%-42s %-6s ' "$ruta" "$metodo"
  for cod in $codigos; do
    if ! aws_api put-method-response --rest-api-id "$API_ID" --resource-id "$rid" \
          --http-method "$metodo" --status-code "$cod" \
          --response-parameters "$CORS_METODO" >/dev/null; then
      printf '[%s MR!] ' "$cod"; fallidos=$((fallidos + 1)); continue
    fi
    if ! aws_api put-integration-response --rest-api-id "$API_ID" --resource-id "$rid" \
          --http-method "$metodo" --status-code "$cod" --selection-pattern "$cod" \
          --response-parameters "$CORS_INTEGRACION" >/dev/null; then
      printf '[%s IR!] ' "$cod"; fallidos=$((fallidos + 1)); continue
    fi
    printf '%s ' "$cod"
    aplicados=$((aplicados + 1))
  done
  printf '\n'
done <<< "$LINEAS"

echo
echo "Pares aplicados: $aplicados · fallidos: $fallidos · $(( $(date +%s) - inicio ))s"

if [ "${SIN_DESPLIEGUE:-0}" = "1" ]; then
  echo "SIN_DESPLIEGUE=1: el stage NO se republico; los cambios no estan en vivo."
  exit 0
fi

echo
echo "Republicando el stage $STAGE ..."
aws_api create-deployment --rest-api-id "$API_ID" --stage-name "$STAGE" \
  --description "Respuestas de error en /api/*: el codigo del servicio llega al navegador" \
  >/dev/null && echo "Listo. Una ruta recien publicada puede dar 403 o 500 durante ~1 minuto: es propagacion del stage."
