#!/usr/bin/env bash
# =============================================================================
#  06-reanudar-ev3.sh — Pone todo en marcha tras un reinicio del lab
#
#  CUANDO USARLO: cada vez que el Learner Lab se detuvo y volviste a arrancarlo.
#  La instancia conserva el disco, pero la IP PUBLICA CAMBIA, y con eso quedan
#  mal tres cosas que la tienen escrita:
#
#     1. infra/.env          -> KAFKA_HOST / advertised.listeners
#     2. las unidades systemd -> bootstrap de Kafka y CORS_ORIGENES
#     3. el contenedor del front -> las URL de las API
#
#  Si no se regeneran, el sintoma es enganoso: los contenedores estan "healthy"
#  y los servicios "active", pero ningun cliente externo puede producir ni
#  consumir, y el navegador bloquea todo por CORS. Nada de eso dice "la IP
#  cambio": hay que saberlo.
#
#  NO recompila (los jar ya estan en el disco), asi que tarda ~2 minutos.
#
#  Uso:  ./06-reanudar-ev3.sh
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO: corre 01-infra-ev3.sh primero"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

DIR="/home/ec2-user/proyecto"
DIR_FRONT="/home/ec2-user/front"
log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }

ssm_run(){
  local desc="$1" script="$2" tmo="${3:-400}" b64 cid estado salida intentos
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
  echo "$salida" | tail -28 | sed 's/^/    /'
  if [ "$estado" != "Success" ]; then
    echo "    --- error ---"
    aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
      --instance-id "$INSTANCIA" --query 'StandardErrorContent' --output text 2>/dev/null \
      | tail -20 | sed 's/^/    /'
    return 1
  fi
}

# -------------------------------------------------- arrancar si hace falta
log "Estado de la instancia"
EST=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
       --query 'Reservations[0].Instances[0].State.Name' --output text)
if [ "$EST" != "running" ]; then
  echo "    esta '$EST', arrancando..."
  aws ec2 start-instances --region "$REGION" --instance-ids "$INSTANCIA" >/dev/null
  aws ec2 wait instance-running --region "$REGION" --instance-ids "$INSTANCIA"
  echo "    esperando al agente SSM..."
  for _ in $(seq 1 24); do
    sleep 10
    N=$(aws ssm describe-instance-information --region "$REGION" \
         --filters "Key=InstanceIds,Values=$INSTANCIA" \
         --query 'length(InstanceInformationList)' --output text 2>/dev/null || echo 0)
    [ "$N" = "1" ] && break
  done
fi

IP_NUEVA=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
            --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
ok "corriendo en $IP_NUEVA"
[ "$IP_NUEVA" != "$IP" ] && echo "    (la IP cambio: antes $IP)"
sed -i "s|^IP=.*|IP=$IP_NUEVA|" "$ESTADO"

# -------------------------------------------------- Kafka con la IP nueva
ssm_run "Regenerando infra/.env y recreando el cluster" "
set -e
cat > '$DIR/infra/.env' <<EOF
# Regenerado por 06-reanudar-ev3.sh. La IP cambia en cada arranque del lab.
KAFKA_HOST=$IP_NUEVA
KAFKA_BOOTSTRAP=$IP_NUEVA:9092,$IP_NUEVA:9093,$IP_NUEVA:9094
EOF
chown ec2-user:ec2-user '$DIR/infra/.env'
cat '$DIR/infra/.env' | grep KAFKA_HOST
cd '$DIR/infra'
# up -d recrea solo los contenedores cuya configuracion cambio.
docker compose up -d
sleep 20
docker compose ps --format '{{.Name}} {{.Status}}'
docker start mysql-pagos-db >/dev/null 2>&1 || true
" 500

# -------------------------------------------------- microservicios
ssm_run "Actualizando las unidades systemd con la IP nueva" "
set -e
BOOT='$IP_NUEVA:9092,$IP_NUEVA:9093,$IP_NUEVA:9094'
CORS='http://$IP_NUEVA:8090'
for s in ms-pago ms-recomendaciones ms-kafka-admin; do
  sed -i \"s|^Environment=SPRING_KAFKA_BOOTSTRAP_SERVERS=.*|Environment=SPRING_KAFKA_BOOTSTRAP_SERVERS=\$BOOT|\" /etc/systemd/system/\$s.service
  sed -i \"s|^Environment=CORS_ORIGENES=.*|Environment=CORS_ORIGENES=\$CORS|\" /etc/systemd/system/\$s.service
done
systemctl daemon-reload
for s in ms-pago ms-recomendaciones ms-kafka-admin; do
  systemctl restart \$s
  printf '%-22s %s\n' \"\$s\" \"\$(systemctl is-active \$s)\"
done
" 400

# -------------------------------------------------- front
ssm_run "Recreando el front con las URL nuevas" "
set -e
if [ -d '$DIR_FRONT' ]; then
  docker rm -f consola-front >/dev/null 2>&1 || true
  docker run -d --name consola-front -p 8090:80 --restart unless-stopped \
    -e API_PAGOS='http://$IP_NUEVA:8083' \
    -e API_RECOMENDACIONES='http://$IP_NUEVA:8084' \
    -e API_ADMIN='http://$IP_NUEVA:8085' \
    pedidos360/consola:1.0.0 >/dev/null 2>&1 && echo 'front recreado' \
    || echo 'el front no esta instalado todavia (corre 05-front-ev3.sh)'
else
  echo 'el front no esta clonado todavia (corre 05-front-ev3.sh)'
fi
" 200

# -------------------------------------------------- verificacion
ssm_run "Verificacion de punta a punta" "
sleep 40
echo '--- endpoints:'
printf 'ms-pago   /api/alertas  -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 http://localhost:8083/api/alertas
printf 'ms-recom  /api/metricas -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 http://localhost:8084/api/metricas
printf 'ms-admin  /api/topicos  -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 http://localhost:8085/api/topicos
printf 'kafka-ui                -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 http://localhost:8080/
printf 'consola   :8090         -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 10 http://localhost:8090/ 2>/dev/null || echo 'no instalada'
echo '--- topicos vistos por el administrador:'
curl -s --max-time 10 http://localhost:8085/api/topicos | head -c 300
echo
echo '--- memoria:'
free -m | awk '/Mem:/ {print \"   usada \" \$3 \" MB de \" \$2 \", libre \" \$7 \" MB\"}'
" 300

log "Reanudacion lista"
cat <<EOF
    Kafka UI            http://$IP_NUEVA:8080
    Consola             http://$IP_NUEVA:8090
    ms-pago             http://$IP_NUEVA:8083/api/alertas
    ms-recomendaciones  http://$IP_NUEVA:8084/api/metricas
    ms-kafka-admin      http://$IP_NUEVA:8085/api/topicos

    Al terminar de trabajar, para no gastar credito:
      aws ec2 stop-instances --region $REGION --instance-ids $INSTANCIA
EOF
