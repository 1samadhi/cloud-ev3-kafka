#!/usr/bin/env bash
# =============================================================================
#  11-arquitectura-nueva.sh — Despliega la version de 7 microservicios en AWS
#
#  QUE HACE, en orden:
#     1. Sube la instancia a t3.large   (el sistema pide ~4,6 GB; t3.medium da 4)
#     2. Amplia el disco si hace falta  (9 imagenes Java + capas de build)
#     3. Actualiza el repo del backend y clona los dos frontends AL LADO,
#        que es como el compose raiz espera encontrarlos
#     4. docker compose up -d --build
#     5. Corre infra/prueba-humo.py (25 comprobaciones contra servicios reales)
#
#  LA ELASTIC IP hace que la direccion no cambie al apagar y prender, asi que
#  no hay que regenerar configuraciones ni rehacer API Gateway.
#
#  EL BUILD TARDA: compila siete servicios Java y dos frontends. Entre 20 y 40
#  minutos la primera vez, y es normal que parezca colgado.
#
#  Uso:  ./11-arquitectura-nueva.sh
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

TIPO_NUEVO="${TIPO_NUEVO:-t3.large}"
DISCO_MIN=50
DIR="/home/ec2-user/proyecto"
REPO_BACK="https://github.com/1samadhi/cloud-ev3-kafka.git"
REPO_CONSOLA="https://github.com/1samadhi/cloud-ev3-front.git"
REPO_TIENDA="https://github.com/DiVilota/pedidos360-tienda.git"

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }

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
  intentos=$(( tmo / 15 + 8 ))
  for _ in $(seq 1 "$intentos"); do
    sleep 15
    estado=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
              --instance-id "$INSTANCIA" --query Status --output text 2>/dev/null || echo Pending)
    case "$estado" in Success|Failed|TimedOut|Cancelled) break ;; esac
  done
  salida=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
            --instance-id "$INSTANCIA" --query 'StandardOutputContent' --output text 2>/dev/null)
  echo "$salida" | tail -40 | sed 's/^/    /'
  if [ "$estado" != "Success" ]; then
    echo "    --- error ---"
    aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
      --instance-id "$INSTANCIA" --query 'StandardErrorContent' --output text 2>/dev/null \
      | tail -25 | sed 's/^/    /'
    return 1
  fi
}

# ---------------------------------------------------- 1. tipo de instancia
log "Tipo de instancia"
TIPO_ACTUAL=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
               --query 'Reservations[0].Instances[0].InstanceType' --output text)
echo "    actual: $TIPO_ACTUAL  ·  objetivo: $TIPO_NUEVO"

if [ "$TIPO_ACTUAL" != "$TIPO_NUEVO" ]; then
  echo "    apagando para cambiar el tipo..."
  aws ec2 stop-instances --region "$REGION" --instance-ids "$INSTANCIA" >/dev/null
  aws ec2 wait instance-stopped --region "$REGION" --instance-ids "$INSTANCIA"
  aws ec2 modify-instance-attribute --region "$REGION" --instance-id "$INSTANCIA" \
    --instance-type "{\"Value\":\"$TIPO_NUEVO\"}"
  ok "cambiada a $TIPO_NUEVO"

  # El disco se amplia con la instancia detenida.
  VOL=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
         --query 'Reservations[0].Instances[0].BlockDeviceMappings[0].Ebs.VolumeId' --output text)
  TAM=$(aws ec2 describe-volumes --region "$REGION" --volume-ids "$VOL" \
         --query 'Volumes[0].Size' --output text)
  if [ "$TAM" -lt "$DISCO_MIN" ]; then
    aws ec2 modify-volume --region "$REGION" --volume-id "$VOL" --size "$DISCO_MIN" >/dev/null
    ok "disco ampliado de ${TAM}GB a ${DISCO_MIN}GB"
  else
    ok "disco ya tiene ${TAM}GB"
  fi

  aws ec2 start-instances --region "$REGION" --instance-ids "$INSTANCIA" >/dev/null
  aws ec2 wait instance-running --region "$REGION" --instance-ids "$INSTANCIA"
  echo "    esperando al agente SSM..."
  for _ in $(seq 1 30); do
    sleep 10
    N=$(aws ssm describe-instance-information --region "$REGION" \
         --filters "Key=InstanceIds,Values=$INSTANCIA" \
         --query 'length(InstanceInformationList)' --output text 2>/dev/null || echo 0)
    [ "$N" = "1" ] && break
  done
  ok "instancia lista"
else
  ok "ya es $TIPO_NUEVO"
fi

IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
sed -i "s|^IP=.*|IP=$IP|" "$ESTADO"
ok "IP: $IP  (fija por la Elastic IP)"

ssm_run "Ampliando el sistema de archivos al disco nuevo" "
set -e
growpart /dev/nvme0n1 1 2>/dev/null || growpart /dev/xvda 1 2>/dev/null || echo 'particion ya ampliada'
xfs_growfs / 2>/dev/null || resize2fs /dev/nvme0n1p1 2>/dev/null || echo 'fs ya ampliado'
df -h / | awk 'NR==2 {print \"   disco: \" \$2 \" total, \" \$4 \" libre\"}'
free -m | awk '/Mem:/ {print \"   RAM: \" \$2 \" MB\"}'
" 300

# ---------------------------------------------------- 2. parar lo viejo
ssm_run "Deteniendo la version anterior" "
for s in ms-pago ms-recomendaciones ms-kafka-admin; do
  systemctl stop \$s 2>/dev/null && echo \"detenido \$s\" || true
  systemctl disable \$s 2>/dev/null || true
done
cd '$DIR/infra' 2>/dev/null && docker compose down 2>&1 | tail -3 || true
docker rm -f consola-front 2>/dev/null || true
echo '--- contenedores que quedan:'
docker ps --format '{{.Names}}' | tr '\n' ' '
" 400

# ---------------------------------------------------- 3. codigo nuevo
ssm_run "Trayendo el codigo nuevo y los dos frontends" "
set -e
git config --global --add safe.directory '$DIR' || true
cd '$DIR'
git fetch -q origin
git reset -q --hard origin/master
git log --oneline -1
echo '--- microservicios:'
ls -d ms-*/ | tr '\n' ' '
echo
echo '--- clonando los frontends AL LADO, como espera el compose:'
cd /home/ec2-user
for r in consola:$REPO_CONSOLA tienda:$REPO_TIENDA; do
  n=\${r%%:*}; u=\${r#*:}
  git config --global --add safe.directory /home/ec2-user/\$n || true
  if [ -d \"/home/ec2-user/\$n/.git\" ]; then
    (cd \$n && git fetch -q origin && git reset -q --hard origin/HEAD 2>/dev/null || git reset -q --hard origin/master 2>/dev/null || git reset -q --hard origin/main) && echo \"\$n actualizado\"
  else
    rm -rf \$n && git clone -q \$u \$n && echo \"\$n clonado\"
  fi
done
chown -R ec2-user:ec2-user /home/ec2-user/proyecto /home/ec2-user/consola /home/ec2-user/tienda 2>/dev/null || true
ls -d /home/ec2-user/*/ | tr '\n' ' '
" 700

# ---------------------------------------------------- 4. .env
ssm_run "Preparando el .env" "
set -e
cd '$DIR'
[ -f .env.example ] && cp -n .env.example .env 2>/dev/null || true
# rutas a los frontends y la IP publica para los listeners
sed -i \"s|^CONSOLA_DIR=.*|CONSOLA_DIR=/home/ec2-user/consola|\" .env 2>/dev/null || echo 'CONSOLA_DIR=/home/ec2-user/consola' >> .env
sed -i \"s|^TIENDA_DIR=.*|TIENDA_DIR=/home/ec2-user/tienda|\" .env 2>/dev/null || echo 'TIENDA_DIR=/home/ec2-user/tienda' >> .env
grep -q '^KAFKA_HOST=' .env && sed -i \"s|^KAFKA_HOST=.*|KAFKA_HOST=$IP|\" .env || echo \"KAFKA_HOST=$IP\" >> .env
echo '--- .env:'
grep -vE 'PASSWORD|SECRET|TOKEN|KEY' .env | head -20
" 300

# ---------------------------------------------------- 5. levantar
ssm_run "docker compose up --build  (20-40 min la primera vez)" "
set -e
cd '$DIR'
docker compose up -d --build 2>&1 | tail -25
echo '--- contenedores:'
docker compose ps --format '{{.Name}} {{.Status}}' 2>/dev/null | head -30
" 2400

ssm_run "Estado y memoria" "
sleep 40
echo '--- contenedores arriba:'
docker ps --format '{{.Names}}\t{{.Status}}' | head -30
echo
echo '--- total:' \$(docker ps -q | wc -l) 'corriendo,' \$(docker ps -aq | wc -l) 'creados'
free -m | awk '/Mem:/ {print \"--- RAM: usada \" \$3 \" de \" \$2 \", libre \" \$7}'
df -h / | awk 'NR==2 {print \"--- disco: \" \$4 \" libre de \" \$2}'
" 400

ssm_run "Prueba de humo (25 comprobaciones)" "
cd '$DIR'
if [ -f infra/prueba-humo.py ]; then
  python3 infra/prueba-humo.py 2>&1 | tail -35
else
  echo 'no encontre infra/prueba-humo.py'
  ls infra/ 2>/dev/null
fi
" 600

log "Despliegue de la arquitectura nueva terminado"
echo "    IP: $IP"
