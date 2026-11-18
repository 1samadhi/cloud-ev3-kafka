#!/usr/bin/env bash
# =============================================================================
#  04-microservicios-ev3.sh — FASE 4: MySQL + los 3 microservicios en la EC2
#
#  1. Levanta MySQL en un contenedor, en la misma red que Kafka.
#  2. Instala Maven y compila los tres modulos.
#  3. Crea una unidad systemd por microservicio y los arranca.
#
#  POR QUE systemd Y NO 'nohup java -jar &': con systemd los servicios vuelven
#  solos si la instancia se reinicia o si una JVM muere, quedan los logs en
#  journalctl y se consultan con 'systemctl status'. Un nohup se pierde en el
#  primer reinicio, y en una demo eso es la diferencia entre mostrar el sistema
#  y pedir disculpas.
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO: corre 01 primero"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

DIR="/home/ec2-user/proyecto"
MYSQL_PASS="123456"
log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

ssm_run(){   # $1 descripcion, $2 script, $3 timeout segundos (opcional)
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
      | tail -25 | sed 's/^/    /'
    return 1
  fi
}

IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
sed -i "s|^IP=.*|IP=$IP|" "$ESTADO"
echo "    instancia en $IP"

# --------------------------------------------------------------- MySQL
ssm_run "MySQL en contenedor, en la red de Kafka" "
set -e
RED=\$(docker network ls --format '{{.Name}}' | grep -E 'infra_default' | head -1)
echo \"red de kafka: \$RED\"
if docker ps -a --format '{{.Names}}' | grep -q '^mysql-pagos-db\$'; then
  docker start mysql-pagos-db >/dev/null 2>&1 || true
  echo 'mysql ya existia, arrancado'
else
  docker run -d --name mysql-pagos-db --network \"\$RED\" \
    -e MYSQL_ROOT_PASSWORD=$MYSQL_PASS -e MYSQL_DATABASE=pagos_db \
    -p 3306:3306 --restart unless-stopped mysql:8.0 >/dev/null
  echo 'mysql creado'
fi
echo 'esperando a que acepte conexiones...'
for i in \$(seq 1 30); do
  if docker exec mysql-pagos-db mysqladmin ping -uroot -p$MYSQL_PASS --silent 2>/dev/null; then
    echo \"mysql listo (intento \$i)\"; break
  fi
  sleep 5
done
docker exec mysql-pagos-db mysql -uroot -p$MYSQL_PASS -e 'SHOW DATABASES;' 2>/dev/null | tr '\n' ' '
" 400

# --------------------------------------------------------------- Maven
ssm_run "Instalando Maven" "
set -e
if command -v mvn >/dev/null 2>&1; then
  mvn -v | head -1
else
  dnf install -y -q maven >/dev/null 2>&1 && echo 'maven instalado por dnf' || {
    echo 'dnf no lo tiene, bajando el binario oficial'
    cd /opt
    curl -sSL https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz -o m.tgz
    tar xzf m.tgz && rm -f m.tgz
    ln -sf /opt/apache-maven-3.9.9/bin/mvn /usr/local/bin/mvn
  }
  mvn -v | head -1
fi
" 600

log "Compilando los tres modulos (tarda: baja dependencias)"
ssm_run "Compilacion" "
set -e
cd '$DIR'
for m in ms-pago ms-recomendaciones ms-kafka-admin; do
  echo \"--- compilando \$m\"
  (cd \"\$m\" && mvn -B -q package -DskipTests 2>&1 | tail -5)
  ls -la \"\$m/target/\"*.jar 2>/dev/null | awk '{print \"    \" \$NF}'
done
" 1500

# --------------------------------------------------- unidades systemd
ssm_run "Creando los servicios de systemd" "
set -e
BOOT='$IP:9092,$IP:9093,$IP:9094'
JDBC='jdbc:mysql://localhost:3306/pagos_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true'
CORS='http://$IP:8090'

unidad(){  # nombre, jar, puerto, con_bd
  cat > /etc/systemd/system/\$1.service <<EOF
[Unit]
Description=Pedidos360 \$1
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=$DIR/\$1
Environment=SERVER_PORT=\$3
Environment=SPRING_KAFKA_BOOTSTRAP_SERVERS=\$BOOT
Environment=CORS_ORIGENES=\$CORS
\$([ \"\$4\" = si ] && echo \"Environment=SPRING_DATASOURCE_URL=\$JDBC\")
ExecStart=/usr/bin/java -Xmx320m -jar $DIR/\$1/target/\$2
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
  echo \"unidad \$1 escrita\"
}

unidad ms-pago            ms-pago-1.0.0.jar            8083 si
unidad ms-recomendaciones ms-recomendaciones-1.0.0.jar 8084 si
unidad ms-kafka-admin     ms-kafka-admin-1.0.0.jar     8085 no

systemctl daemon-reload
for s in ms-pago ms-recomendaciones ms-kafka-admin; do
  systemctl enable --now \$s >/dev/null 2>&1
  echo \"\$s -> \$(systemctl is-active \$s)\"
done
" 400

log "Esperando a que los tres arranquen"
ssm_run "Estado de los servicios" "
sleep 45
for s in ms-pago ms-recomendaciones ms-kafka-admin; do
  printf '%-22s %s\n' \"\$s\" \"\$(systemctl is-active \$s)\"
done
echo '--- puertos escuchando:'
ss -ltnp 2>/dev/null | grep -E ':(8083|8084|8085|8080|3306)' | awk '{print \"   \" \$4}'
echo '--- memoria:'
free -m | awk '/Mem:/ {print \"   total \" \$2 \" MB, usada \" \$3 \" MB, libre \" \$7 \" MB\"}'
" 300

log "FASE 4 lista"
cat <<EOF
    ms-pago            http://$IP:8083/api/alertas
    ms-recomendaciones http://$IP:8084/api/metricas
    ms-kafka-admin     http://$IP:8085/api/topicos
    Kafka UI           http://$IP:8080
EOF
