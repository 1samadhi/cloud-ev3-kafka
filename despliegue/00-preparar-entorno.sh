#!/usr/bin/env bash
# =============================================================================
#  00-preparar-entorno.sh — Arregla la instancia ANTES de desplegar
#
#  POR QUE EXISTE
#  La AMI de Amazon Linux 2023 trae dos versiones que NO alcanzan, y las dos
#  fallan de formas confusas:
#
#    1. buildx 0.12  ->  "compose build requires buildx 0.17.0 or later"
#       El build no construye NADA y el mensaje no dice que hacer.
#
#    2. Python 3.9   ->  "TypeError: unsupported operand type(s) for |:
#                         'type' and 'NoneType'"
#       La prueba de humo usa  str | None , sintaxis de Python 3.10+.
#
#  Correr esto una vez por instancia nueva, antes del 11.
#
#  Uso:  ./00-preparar-entorno.sh
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO — corre primero 01-infra-ev3.sh"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

VERSION_BUILDX="v0.19.3"

echo "==> Preparando la instancia $INSTANCIA"

SCRIPT="set -e
echo '--- buildx antes:'
docker buildx version 2>&1 | head -1

echo '--- instalando buildx $VERSION_BUILDX:'
mkdir -p /usr/libexec/docker/cli-plugins /root/.docker/cli-plugins /home/ec2-user/.docker/cli-plugins
curl -sSL -o /tmp/bx 'https://github.com/docker/buildx/releases/download/$VERSION_BUILDX/buildx-$VERSION_BUILDX.linux-amd64'
for d in /usr/libexec/docker/cli-plugins /root/.docker/cli-plugins /home/ec2-user/.docker/cli-plugins; do
  install -m 0755 /tmp/bx \$d/docker-buildx
done
chown -R ec2-user:ec2-user /home/ec2-user/.docker
echo '--- buildx despues:'
docker buildx version 2>&1 | head -1

echo '--- python antes:' \$(python3 --version)
dnf install -y python3.11 >/dev/null 2>&1 && echo '--- python3.11:' \$(python3.11 --version)

echo '--- metadata de EC2 alcanzable desde un contenedor?'
docker run --rm --network host amazon/aws-cli:latest sts get-caller-identity --query Arn --output text 2>&1 | tail -1
"

B=$(printf '%s' "$SCRIPT" | base64 -w0)
CID=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCIA" \
       --document-name AWS-RunShellScript \
       --parameters "commands=echo $B | base64 -d | bash" \
       --timeout-seconds 600 --query 'Command.CommandId' --output text)

for _ in $(seq 1 40); do
  sleep 15
  E=$(aws ssm get-command-invocation --region "$REGION" --command-id "$CID" \
       --instance-id "$INSTANCIA" --query Status --output text 2>/dev/null || echo Pending)
  case "$E" in Success|Failed|TimedOut) break ;; esac
done

aws ssm get-command-invocation --region "$REGION" --command-id "$CID" \
  --instance-id "$INSTANCIA" --query 'StandardOutputContent' --output text | sed 's/^/    /'

# El metadata necesita hop limit 2 para que un CONTENEDOR lo alcance; por
# defecto es 1 y el salto extra de la red de Docker lo deja afuera. Sin esto,
# ms-recomendaciones no puede usar el rol de la instancia para DynamoDB.
echo
echo "==> Metadata de EC2: hop limit a 2"
aws ec2 modify-instance-metadata-options --region "$REGION" --instance-id "$INSTANCIA" \
  --http-put-response-hop-limit 2 --http-tokens optional --http-endpoint enabled \
  --query 'InstanceMetadataOptions.[HttpTokens,HttpPutResponseHopLimit]' --output text

echo
echo "Listo. Ahora se puede correr 11-arquitectura-nueva.sh"
