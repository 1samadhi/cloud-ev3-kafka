#!/usr/bin/env bash
# =============================================================================
#  07-compilar-ev3.sh — Instala el JDK 21 completo y compila los tres modulos
#
#  POR QUE EXISTE: el user-data instalaba 'java-21-amazon-corretto-headless',
#  que es el RUNTIME (trae 'java' pero NO 'javac'). Maven caia al JDK por
#  defecto del sistema, mas viejo, y fallaba con:
#       error: release version 21 not supported
#  El sintoma era enganoso: systemd mostraba los servicios 'activating' y
#  reiniciandolos en bucle, porque el jar nunca se habia generado.
#
#  Para COMPILAR hace falta el paquete -devel. Es la diferencia JRE / JDK.
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

DIR="/home/ec2-user/proyecto"
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
  echo "$salida" | tail -32 | sed 's/^/    /'
  if [ "$estado" != "Success" ]; then
    echo "    --- error ---"
    aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
      --instance-id "$INSTANCIA" --query 'StandardErrorContent' --output text 2>/dev/null \
      | tail -20 | sed 's/^/    /'
    return 1
  fi
}

ssm_run "Instalando el JDK 21 completo (con javac)" "
set -e
dnf install -y -q java-21-amazon-corretto-devel 2>&1 | tail -3 || true
echo '--- javac disponible:'
javac -version 2>&1 || echo 'NO hay javac en el PATH'
echo '--- JDKs instalados:'
ls -d /usr/lib/jvm/*/ 2>/dev/null | sed 's/^/   /'
" 600

# Detener los servicios mientras compila: estan en bucle de reinicio y compiten
# por memoria con Maven, que en una t3.medium con Kafka arriba es escasa.
ssm_run "Deteniendo los servicios para liberar memoria" "
for s in ms-pago ms-recomendaciones ms-kafka-admin; do systemctl stop \$s 2>/dev/null || true; done
free -m | awk '/Mem:/ {print \"   libre: \" \$7 \" MB\"}'
" 200

ssm_run "Compilando con JAVA_HOME apuntando al JDK 21" "
set -e
export JAVA_HOME=\$(dirname \$(dirname \$(readlink -f \$(which javac))))
echo \"JAVA_HOME=\$JAVA_HOME\"
\$JAVA_HOME/bin/javac -version
cd '$DIR'
for m in ms-pago ms-recomendaciones ms-kafka-admin; do
  echo \"--- \$m\"
  (cd \"\$m\" && JAVA_HOME=\$JAVA_HOME mvn -B -q package -DskipTests 2>&1 | grep -E 'ERROR|BUILD' | head -4)
  ls -la \"\$m\"/target/*.jar 2>/dev/null | awk '{printf \"    %s  %s\n\", \$5, \$NF}' || echo '    SIN JAR'
done
chown -R ec2-user:ec2-user '$DIR'
" 1500

ssm_run "Arrancando los servicios" "
set -e
for s in ms-pago ms-recomendaciones ms-kafka-admin; do
  systemctl reset-failed \$s 2>/dev/null || true
  systemctl restart \$s
done
sleep 50
for s in ms-pago ms-recomendaciones ms-kafka-admin; do
  printf '%-22s %s\n' \"\$s\" \"\$(systemctl is-active \$s)\"
done
echo '--- endpoints:'
printf '8083 /api/alertas  -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 12 http://localhost:8083/api/alertas
printf '8084 /api/metricas -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 12 http://localhost:8084/api/metricas
printf '8085 /api/topicos  -> '; curl -s -o /dev/null -w '%{http_code}\n' --max-time 12 http://localhost:8085/api/topicos
echo '--- respuesta del administrador:'
curl -s --max-time 12 http://localhost:8085/api/topicos | head -c 300
echo
free -m | awk '/Mem:/ {print \"--- memoria: usada \" \$3 \" de \" \$2 \", libre \" \$7}'
" 400

log "Compilacion y arranque terminados"
