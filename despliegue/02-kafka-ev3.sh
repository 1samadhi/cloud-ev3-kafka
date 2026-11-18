#!/usr/bin/env bash
# =============================================================================
#  02-kafka-ev3.sh — FASE 2: el cluster Kafka de la EV3 dentro de la EC2
#
#  Clona el repo publico en la instancia, genera el .env con la IP PUBLICA y
#  levanta 3 Zookeeper + 3 brokers + Kafka UI. Despues crea los dos topicos.
#
#  POR QUE LA IP PUBLICA EN EL .env: 'advertised.listeners' es la direccion que
#  el broker le RESPONDE al cliente cuando este se conecta. Si anuncia
#  'localhost', un cliente remoto se termina conectando a si mismo y falla con
#  timeouts de metadata que no dicen nada util. Por eso cambia en cada arranque
#  de la instancia, y por eso el .env se regenera en vez de versionarse.
#
#  Uso:  ./02-kafka-ev3.sh
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO: corre primero 01-infra-ev3.sh"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

REPO="https://github.com/1samadhi/cloud-ev3-kafka.git"
DIR="/home/ec2-user/proyecto"

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }

# Refresca la IP: pudo cambiar si la instancia se reinicio.
IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
EST=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
      --query 'Reservations[0].Instances[0].State.Name' --output text)
[ "$EST" = "running" ] || { echo "la instancia esta '$EST', arrancala primero"; exit 1; }
ok "instancia $INSTANCIA corriendo en $IP"
sed -i "s|^IP=.*|IP=$IP|" "$ESTADO"

# Ejecuta un script en la instancia por SSM y espera el resultado.
#
# El script viaja en BASE64: pasarlo como texto dentro del JSON de --parameters
# corrompe los saltos de linea (se vieron 'nset' y 'thenn', restos de 'set' y
# 'then' pegados a la linea anterior). En base64 no hay nada que escapar.
ssm_run(){   # $1 = descripcion, $2 = script
  local desc="$1" script="$2" b64 cid estado salida
  log "$desc"
  # SSM ejecuta sin HOME y sin PATH completo: git falla con '$HOME not set' y
  # los binarios de /usr/local/bin (compose) no se encuentran. Se antepone a
  # todo script para no repetirlo en cada llamada.
  script="export HOME=/root PATH=/usr/local/bin:/usr/bin:/bin:\$PATH
$script"
  b64=$(printf '%s' "$script" | base64 -w0)
  cid=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCIA" \
        --document-name AWS-RunShellScript \
        --parameters "commands=echo $b64 | base64 -d | bash" \
        --timeout-seconds 600 \
        --query 'Command.CommandId' --output text)
  for _ in $(seq 1 60); do
    sleep 10
    estado=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
              --instance-id "$INSTANCIA" --query Status --output text 2>/dev/null || echo Pending)
    case "$estado" in
      Success|Failed|TimedOut|Cancelled) break ;;
    esac
  done
  salida=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
            --instance-id "$INSTANCIA" --query 'StandardOutputContent' --output text 2>/dev/null)
  echo "$salida" | sed 's/^/    /'
  if [ "$estado" != "Success" ]; then
    echo "    --- error ---"
    aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
      --instance-id "$INSTANCIA" --query 'StandardErrorContent' --output text 2>/dev/null \
      | tail -25 | sed 's/^/    /'
    return 1
  fi
}

ssm_run "Clonando el repositorio en la instancia" "
set -e
# SSM ejecuta como root y el repo pertenece a ec2-user: sin esto git aborta con
# 'detected dubious ownership'. Es una proteccion contra repos ajenos, y aca el
# dueño es conocido.
git config --global --add safe.directory '$DIR' || true
if [ -d '$DIR/.git' ]; then
  cd '$DIR' && git fetch -q origin && git reset -q --hard origin/master
  echo 'repo actualizado'
else
  rm -rf '$DIR'
  git clone -q '$REPO' '$DIR'
  echo 'repo clonado'
fi
chown -R ec2-user:ec2-user '$DIR'
git -C '$DIR' log --oneline -1
ls '$DIR/infra/'
"

ssm_run "Generando infra/.env con la IP publica" "
set -e
cat > '$DIR/infra/.env' <<EOF
# Generado por 02-kafka-ev3.sh. La IP cambia en cada arranque: NO versionar.
KAFKA_HOST=$IP
KAFKA_BOOTSTRAP=$IP:9092,$IP:9093,$IP:9094
EOF
chown ec2-user:ec2-user '$DIR/infra/.env'
cat '$DIR/infra/.env'
"

ssm_run "Levantando el cluster (descarga imagenes, puede tardar)" "
set -e
cd '$DIR/infra'
docker compose up -d
sleep 25
docker compose ps --format '{{.Name}} {{.Status}}'
"

log "FASE 2 lanzada"
echo "    Kafka UI quedara en:  http://$IP:8080"
echo "    Bootstrap para clientes externos: $IP:9092,$IP:9093,$IP:9094"
echo
echo "    Los contenedores tardan ~2 min en ponerse 'healthy'."
echo "    Siguiente: crear los topicos con 03-topicos-ev3.sh"
