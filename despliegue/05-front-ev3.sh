#!/usr/bin/env bash
# =============================================================================
#  05-front-ev3.sh — FASE 5: la consola de operaciones servida con nginx
#
#  Clona el repo del front en la EC2, construye la imagen y la levanta en 8090
#  apuntando a los microservicios de ESTA instancia.
#
#  POR QUE VA EN UN CONTENEDOR Y NO COMO ARCHIVO SUELTO: un archivo abierto con
#  file:// tiene origen "null", y un origen null solo pasa CORS con comodin.
#  El docente observo en la EV2 que el CORS debe declarar la ruta concreta del
#  frontend, asi que el front necesita un origen HTTP real. De ahi nginx.
#
#  Las URL de las API NO van escritas en el HTML: el entrypoint genera config.js
#  en cada arranque desde variables de entorno, asi la misma imagen sirve en
#  local y en AWS. Hoy apuntan directo a los puertos; cuando exista API Gateway
#  se cambian las variables y nada mas.
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

REPO_FRONT="https://github.com/1samadhi/cloud-ev3-front.git"
DIR_FRONT="/home/ec2-user/front"
log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

ssm_run(){
  local desc="$1" script="$2" tmo="${3:-600}" b64 cid estado salida intentos
  log "$desc"
  script="export HOME=/root PATH=/usr/local/bin:/usr/bin:/bin:\$PATH
$script"
  b64=$(printf '%s' "$script" | base64 -w0)
  cid=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCIA" \
        --document-name AWS-RunShellScript \
        --parameters "commands=echo $b64 | base64 -d | bash" \
        --timeout-seconds "$tmo" --query 'Command.CommandId' --output text)
  intentos=$(( tmo / 10 + 6 ))
  for _ in $(seq 1 "$intentos"); do
    sleep 10
    estado=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
              --instance-id "$INSTANCIA" --query Status --output text 2>/dev/null || echo Pending)
    case "$estado" in Success|Failed|TimedOut|Cancelled) break ;; esac
  done
  salida=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
            --instance-id "$INSTANCIA" --query 'StandardOutputContent' --output text 2>/dev/null)
  echo "$salida" | tail -30 | sed 's/^/    /'
  if [ "$estado" != "Success" ]; then
    echo "    --- error ---"
    aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
      --instance-id "$INSTANCIA" --query 'StandardErrorContent' --output text 2>/dev/null \
      | tail -20 | sed 's/^/    /'
    return 1
  fi
}

IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
sed -i "s|^IP=.*|IP=$IP|" "$ESTADO"

ssm_run "Clonando el front y construyendo la imagen" "
set -e
git config --global --add safe.directory '$DIR_FRONT' || true
if [ -d '$DIR_FRONT/.git' ]; then
  cd '$DIR_FRONT' && git fetch -q origin && git reset -q --hard origin/master
  echo 'front actualizado'
else
  rm -rf '$DIR_FRONT'
  git clone -q '$REPO_FRONT' '$DIR_FRONT'
  echo 'front clonado'
fi
cd '$DIR_FRONT'
docker build -q -t pedidos360/consola:1.0.0 . | tail -1
" 600

ssm_run "Levantando la consola en el puerto 8090" "
set -e
docker rm -f consola-front >/dev/null 2>&1 || true
docker run -d --name consola-front -p 8090:80 --restart unless-stopped \
  -e API_PAGOS='http://$IP:8083' \
  -e API_RECOMENDACIONES='http://$IP:8084' \
  -e API_ADMIN='http://$IP:8085' \
  pedidos360/consola:1.0.0 >/dev/null
sleep 8
docker ps --filter name=consola-front --format '{{.Names}} {{.Status}}'
echo '--- config.js generado:'
curl -s --max-time 8 http://localhost:8090/config.js
echo '--- la pagina responde:'
curl -s -o /dev/null -w 'HTTP %{http_code}\n' --max-time 8 http://localhost:8090/
" 300

ssm_run "Comprobando el CORS contra el origen real" "
echo '--- preflight desde el front ($IP:8090), debe devolver ese origen:'
curl -s -i --max-time 10 -X OPTIONS http://localhost:8084/api/metricas \
  -H 'Origin: http://$IP:8090' -H 'Access-Control-Request-Method: GET' \
  | grep -i '^access-control-allow-origin' | tr -d '\r'
echo '--- preflight desde un origen ajeno, debe ser rechazado:'
curl -s -o /dev/null -w 'HTTP %{http_code}\n' --max-time 10 -X OPTIONS http://localhost:8084/api/metricas \
  -H 'Origin: http://sitio-ajeno.com' -H 'Access-Control-Request-Method: GET'
" 200

log "FASE 5 lista"
echo "    Consola de operaciones:  http://$IP:8090"
