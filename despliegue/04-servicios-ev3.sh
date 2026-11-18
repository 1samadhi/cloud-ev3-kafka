#!/usr/bin/env bash
# =============================================================================
#  04-servicios-ev3.sh — FASE 4: MySQL y los tres microservicios en la EC2
#
#  QUE HACE
#    1. Crea un bucket S3 y sube los JAR ya compilados y verificados en local.
#    2. Levanta MySQL en un contenedor dentro de la instancia.
#    3. Baja los JAR y los registra como servicios de systemd.
#
#  POR QUE S3 Y NO COMPILAR EN LA INSTANCIA: con Kafka arriba quedan ~1,6 GB
#  libres, y tres builds de Spring Boot ahi se arriesgan a que el kernel mate
#  el proceso (OOM) a mitad de camino. Los JAR locales ya estan verificados.
#
#  POR QUE systemd Y NO DOCKER para los microservicios: son tres JVM mas sobre
#  una maquina con RAM justa; systemd evita la capa extra y, sobre todo, los
#  reinicia solos si se caen y los levanta si la instancia se reinicia, que es
#  justo lo que pasa cada vez que se apaga el lab.
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAIZ="$(cd "$AQUI/.." && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO: corre 01, 02 y 03 primero"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

BUCKET="pedidos360-ev3-$CUENTA"
DIR="/home/ec2-user/proyecto"
MYSQL_PASS="123456"
MYSQL_DB="pagos_db"

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }

ssm_run(){
  local desc="$1" script="$2" b64 cid estado salida
  log "$desc"
  script="export HOME=/root PATH=/usr/local/bin:/usr/bin:/bin:\$PATH
$script"
  b64=$(printf '%s' "$script" | base64 -w0)
  cid=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCIA" \
        --document-name AWS-RunShellScript \
        --parameters "commands=echo $b64 | base64 -d | bash" \
        --timeout-seconds 900 --query 'Command.CommandId' --output text)
  for _ in $(seq 1 90); do
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
      | tail -25 | sed 's/^/    /'
    return 1
  fi
}

IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
ok "instancia en $IP"

# ------------------------------------------------------------------ S3
log "Bucket S3 $BUCKET"
if aws s3api head-bucket --bucket "$BUCKET" --region "$REGION" 2>/dev/null; then
  ok "ya existia"
else
  aws s3 mb "s3://$BUCKET" --region "$REGION" >/dev/null
  ok "creado"
fi

log "Subiendo los JAR compilados"
for m in ms-pago ms-recomendaciones ms-kafka-admin; do
  J="$RAIZ/$m/target/$m-1.0.0.jar"
  [ -f "$J" ] || { echo "falta $J (compila con: cd $RAIZ/$m && mvn -B package -DskipTests)"; exit 1; }
  aws s3 cp "$J" "s3://$BUCKET/jars/$m.jar" --region "$REGION" --only-show-errors
  ok "$m ($(du -m "$J" | cut -f1) MB)"
done

# ------------------------------------------------------------------ MySQL
ssm_run "Levantando MySQL" "
set -e
if [ \"\$(docker ps -aq -f name=mysql-pagos-db)\" ]; then
  docker start mysql-pagos-db >/dev/null 2>&1 || true
  echo 'mysql ya existia, arrancado'
else
  docker run -d --name mysql-pagos-db \
    --network infra_default \
    -e MYSQL_ROOT_PASSWORD='$MYSQL_PASS' \
    -e MYSQL_DATABASE='$MYSQL_DB' \
    -p 3306:3306 \
    --health-cmd='mysqladmin ping -h localhost -u root -p$MYSQL_PASS' \
    --health-interval=10s --health-retries=6 \
    mysql:8.0 --default-authentication-plugin=mysql_native_password >/dev/null
  echo 'mysql creado'
fi
for i in \$(seq 1 30); do
  if docker exec mysql-pagos-db mysqladmin ping -h localhost -u root -p$MYSQL_PASS >/dev/null 2>&1; then
    echo \"mysql responde (intento \$i)\"; break
  fi
  sleep 5
done
docker ps --filter name=mysql-pagos-db --format '{{.Names}} {{.Status}}'
"

# ------------------------------------------------- JARs y servicios systemd
ssm_run "Bajando los JAR desde S3" "
set -e
mkdir -p '$DIR/jars'
aws s3 sync 's3://$BUCKET/jars/' '$DIR/jars/' --region '$REGION' --only-show-errors
chown -R ec2-user:ec2-user '$DIR/jars'
ls -la '$DIR/jars/'
"

# Variables comunes. CORS_ORIGENES apunta al front servido en esta misma
# instancia: es la URL concreta que exige la observacion del docente.
COMUN="SPRING_KAFKA_BOOTSTRAP_SERVERS=$IP:9092,$IP:9093,$IP:9094
Environment=CORS_ORIGENES=http://$IP:8090"

ssm_run "Registrando los microservicios como servicios de systemd" "
set -e
crear_unidad(){  # nombre, puerto, extra
  local n=\"\$1\" p=\"\$2\" extra=\"\$3\"
  cat > /etc/systemd/system/\$n.service <<UNIDAD
[Unit]
Description=Pedidos360 \$n
After=network.target docker.service
Wants=docker.service

[Service]
Type=simple
User=ec2-user
WorkingDirectory=$DIR
Environment=SERVER_PORT=\$p
Environment=SPRING_KAFKA_BOOTSTRAP_SERVERS=$IP:9092,$IP:9093,$IP:9094
Environment=CORS_ORIGENES=http://$IP:8090
\$extra
ExecStart=/usr/bin/java -Xmx256m -jar $DIR/jars/\$n.jar
Restart=always
RestartSec=10
StandardOutput=append:/var/log/\$n.log
StandardError=append:/var/log/\$n.log

[Install]
WantedBy=multi-user.target
UNIDAD
}

DS='Environment=SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/$MYSQL_DB?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true'

crear_unidad ms-pago            8083 \"\$DS\"
crear_unidad ms-recomendaciones 8084 \"\$DS\"
crear_unidad ms-kafka-admin     8085 ''

systemctl daemon-reload
for s in ms-pago ms-recomendaciones ms-kafka-admin; do
  systemctl enable \$s >/dev/null 2>&1
  systemctl restart \$s
done
sleep 45
for s in ms-pago ms-recomendaciones ms-kafka-admin; do
  printf '%-22s %s\n' \"\$s\" \"\$(systemctl is-active \$s)\"
done
"

ssm_run "Comprobando que los tres responden" "
sleep 20
for p in 8083:alertas 8084:metricas 8085:topicos; do
  puerto=\${p%%:*}; ruta=\${p##*:}
  printf '  :%s /api/%-10s HTTP %s\n' \"\$puerto\" \"\$ruta\" \
    \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://localhost:\$puerto/api/\$ruta)\"
done
free -m | head -2
"

log "FASE 4 lista"
cat <<EOF
    MySQL              contenedor mysql-pagos-db
    ms-pago            http://$IP:8083/api/alertas
    ms-recomendaciones http://$IP:8084/api/metricas
    ms-kafka-admin     http://$IP:8085/api/topicos
    Kafka UI           http://$IP:8080

    Logs en la instancia: /var/log/ms-*.log
    Siguiente: el front con nginx (fase 5)
EOF
