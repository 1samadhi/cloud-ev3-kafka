#!/usr/bin/env bash
# =============================================================================
#  03-topicos-ev3.sh — FASE 3: crea los dos topicos de la EV3 y los verifica
#
#  Parametros exigidos por la rubrica EP6 (10% del total):
#     3 particiones · 3 replicas · min.insync.replicas=2 · retention 7 dias
#
#  POR QUE min.insync.replicas=2 Y NO 3: con 3, basta que UN broker se caiga
#  para que las escrituras con acks=all fallen y el productor quede bloqueado.
#  Con 2 el cluster tolera la caida de un broker sin perder durabilidad, que es
#  justamente para lo que sirve replicar. Es el valor que recomienda Confluent
#  para factor 3.
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO: corre 01 y 02 primero"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

DIR="/home/ec2-user/proyecto"
RETENCION_MS=604800000       # 7 dias
log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

ssm_run(){
  local desc="$1" script="$2" b64 cid estado salida
  log "$desc"
  script="export HOME=/root PATH=/usr/local/bin:/usr/bin:/bin:\$PATH
$script"
  b64=$(printf '%s' "$script" | base64 -w0)
  cid=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCIA" \
        --document-name AWS-RunShellScript \
        --parameters "commands=echo $b64 | base64 -d | bash" \
        --timeout-seconds 600 --query 'Command.CommandId' --output text)
  for _ in $(seq 1 60); do
    sleep 10
    estado=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
              --instance-id "$INSTANCIA" --query Status --output text 2>/dev/null || echo Pending)
    case "$estado" in Success|Failed|TimedOut|Cancelled) break ;; esac
  done
  salida=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
            --instance-id "$INSTANCIA" --query 'StandardOutputContent' --output text 2>/dev/null)
  echo "$salida" | sed 's/^/    /'
  if [ "$estado" != "Success" ]; then
    echo "    --- error ---"
    aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
      --instance-id "$INSTANCIA" --query 'StandardErrorContent' --output text 2>/dev/null \
      | tail -20 | sed 's/^/    /'
    return 1
  fi
}

ssm_run "Creando los topicos del dominio" "
set -e
cd '$DIR/infra'
crear(){
  docker exec kafka1 kafka-topics --bootstrap-server kafka1:29092 \
    --create --if-not-exists --topic \"\$1\" \
    --partitions 3 --replication-factor 3 \
    --config retention.ms=$RETENCION_MS \
    --config cleanup.policy=delete \
    --config min.insync.replicas=2 2>&1 | tail -1
}
crear payment-attempts
crear interacciones-user
echo '--- topicos existentes:'
docker exec kafka1 kafka-topics --bootstrap-server kafka1:29092 --list | grep -v '^__'
"

ssm_run "Verificando particiones, replicas e ISR" "
set -e
for t in payment-attempts interacciones-user; do
  echo \"=== \$t\"
  docker exec kafka1 kafka-topics --bootstrap-server kafka1:29092 --describe --topic \"\$t\"
done
"

log "FASE 3 lista"
echo "    Kafka UI: http://$IP:8080   (pestaña Topics)"
echo "    Cada topico debe mostrar 3 particiones y 'Isr: 1,2,3' o equivalente."
