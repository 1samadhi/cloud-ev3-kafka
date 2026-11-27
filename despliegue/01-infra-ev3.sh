#!/usr/bin/env bash
# =============================================================================
#  01-infra-ev3.sh — FASE 1 del despliegue de la Evaluacion 3 en AWS
#
#  Crea la base: security group + EC2 con Docker + el cluster Kafka de la EV3
#  (3 Zookeeper + 3 brokers + Kafka UI) + los dos topicos + MySQL.
#
#  NO despliega los microservicios ni el front: eso es la fase 2.
#
#  USO
#     1. Abrir el lab, Start Lab, esperar el circulo verde:
#        https://awsacademy.instructure.com/courses/183051/modules/items/18057489
#     2. ~/.hermes/duoc/aws-cred.sh      (carga las credenciales del portapapeles)
#     3. ./01-infra-ev3.sh
#
#  ES IDEMPOTENTE: si algo existe lo reutiliza. Se puede re-correr tras un fallo.
#
#  POR QUE t3.medium Y NO t3.small: seis JVM de Kafka mas MySQL mas los tres
#  microservicios necesitan ~2,6 GB. t3.small tiene 2 GB y el kernel mata procesos
#  (OOM) en medio de la demo. t3.medium son 4 GB y cuesta ~US$1/dia prendida.
# =============================================================================
set -euo pipefail

REGION="${REGION:-us-east-1}"
NOMBRE_INSTANCIA="pedidos360-ev3"
NOMBRE_SG="grupo-pedidos360"
TIPO="${TIPO:-t3.medium}"
DISCO_GB=30
PERFIL_IAM="LabInstanceProfile"
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
export AWS_PAGER=""

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }
avi(){ printf '    \033[1;33m!!\033[0m %s\n' "$*"; }
die(){ printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- credenciales
log "Comprobando credenciales con una llamada real"
# get-caller-identity responde aun con credenciales canceladas por el lab: no sirve.
aws ec2 describe-vpcs --region "$REGION" --max-items 1 >/dev/null 2>&1 \
  || die "credenciales invalidas o lab detenido. Corre ~/.hermes/duoc/aws-cred.sh"
CUENTA=$(aws sts get-caller-identity --query Account --output text)
ok "cuenta $CUENTA, region $REGION"

# ------------------------------------------------------------------ red
log "Descubriendo VPC y subred por defecto"
VPC=$(aws ec2 describe-vpcs --region "$REGION" --filters Name=isDefault,Values=true \
        --query 'Vpcs[0].VpcId' --output text)
[ "$VPC" != "None" ] || die "no hay VPC por defecto en $REGION"
# No vale tomar "la primera subred por defecto": no todas las zonas ofrecen
# todos los tipos de instancia. us-east-1e no tiene t3.large y RunInstances
# falla con "Unsupported" DESPUES de haber creado ya el security group, lo que
# deja el despliegue a medias. Se pregunta a AWS que zonas ofrecen el tipo y se
# elige una subred que este en alguna de ellas.
log "Buscando una zona que ofrezca $TIPO"
ZONAS_OK=$(aws ec2 describe-instance-type-offerings --region "$REGION" \
            --location-type availability-zone \
            --filters "Name=instance-type,Values=$TIPO" \
            --query 'InstanceTypeOfferings[].Location' --output text | tr -d '\r')
[ -n "$ZONAS_OK" ] || die "$TIPO no se ofrece en ninguna zona de $REGION"
ok "zonas con $TIPO: $(echo "$ZONAS_OK" | tr '\t' ' ')"

SUBNET=""
for Z in $ZONAS_OK; do
  SUBNET=$(aws ec2 describe-subnets --region "$REGION" \
            --filters Name=vpc-id,Values="$VPC" Name=availability-zone,Values="$Z" \
            --query 'Subnets[0].SubnetId' --output text 2>/dev/null | tr -d '\r' || echo None)
  if [ "$SUBNET" != "None" ] && [ -n "$SUBNET" ]; then ZONA="$Z"; break; fi
  SUBNET=""
done
[ -n "$SUBNET" ] || die "ninguna subred de $VPC esta en una zona que ofrezca $TIPO"
ok "VPC $VPC / subred $SUBNET (zona $ZONA)"

# ------------------------------------------------------- security group
log "Security group $NOMBRE_SG"
SG=$(aws ec2 describe-security-groups --region "$REGION" \
      --filters Name=group-name,Values="$NOMBRE_SG" Name=vpc-id,Values="$VPC" \
      --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo None)

if [ "$SG" = "None" ] || [ -z "$SG" ]; then
  SG=$(aws ec2 create-security-group --region "$REGION" --group-name "$NOMBRE_SG" \
        --description "Pedidos360 EV3: Kafka, microservicios y consola" \
        --vpc-id "$VPC" --query GroupId --output text)
  ok "creado $SG"
else
  ok "ya existia $SG"
fi

# Puertos. SIN SSH (22): se entra por SSM, que no necesita par de claves ni
# exponer el puerto. Es mas seguro y el lab no deja crear keypairs comodamente.
abrir(){  # puerto, descripcion
  aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG" \
    --ip-permissions "IpProtocol=tcp,FromPort=$1,ToPort=$1,IpRanges=[{CidrIp=0.0.0.0/0,Description='$2'}]" \
    >/dev/null 2>&1 && ok "abierto $1 ($2)" || avi "$1 ya estaba abierto"
}
abrir 9092 "broker kafka 1"
abrir 9093 "broker kafka 2"
abrir 9094 "broker kafka 3"
abrir 8080 "kafka ui"
abrir 8083 "ms-pago"
abrir 8084 "ms-recomendaciones"
abrir 8085 "ms-kafka-admin"
abrir 8090 "consola nginx"

# ------------------------------------------------------------------ EC2
log "Instancia EC2 $NOMBRE_INSTANCIA ($TIPO)"
ID=$(aws ec2 describe-instances --region "$REGION" \
      --filters Name=tag:Name,Values="$NOMBRE_INSTANCIA" \
                Name=instance-state-name,Values=pending,running,stopping,stopped \
      --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || echo None)

if [ "$ID" = "None" ] || [ -z "$ID" ]; then
  AMI=$(aws ssm get-parameters --region "$REGION" \
         --names /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
         --query 'Parameters[0].Value' --output text)
  ok "AMI $AMI"

  # user-data: deja Docker y git listos antes de que entremos por SSM.
  USERDATA=$(cat <<'UD'
#!/bin/bash
set -x
dnf update -y
dnf install -y docker git
systemctl enable --now docker
usermod -aG docker ec2-user
# Compose v2 como plugin del CLI (docker compose, sin guion)
mkdir -p /usr/local/lib/docker/cli-plugins
curl -sSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
# Java 21 COMPLETO (-devel) para compilar en la instancia.
# NO usar 'java-21-amazon-corretto-headless': ese es solo el runtime, trae
# 'java' pero no 'javac', y Maven cae al JDK viejo del sistema fallando con
# "error: release version 21 not supported". El sintoma enga~na: systemd deja
# los servicios en bucle de reinicio porque el jar nunca se genero.
dnf install -y java-21-amazon-corretto-devel
touch /var/lib/cloud/instance/PEDIDOS360_LISTO
UD
)
  ID=$(aws ec2 run-instances --region "$REGION" --image-id "$AMI" \
        --instance-type "$TIPO" --subnet-id "$SUBNET" --security-group-ids "$SG" \
        --iam-instance-profile Name="$PERFIL_IAM" \
        --block-device-mappings "DeviceName=/dev/xvda,Ebs={VolumeSize=$DISCO_GB,VolumeType=gp3}" \
        --metadata-options "HttpTokens=required,HttpEndpoint=enabled" \
        --user-data "$USERDATA" \
        --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$NOMBRE_INSTANCIA}]" \
        --query 'Instances[0].InstanceId' --output text)
  ok "creada $ID"
else
  ok "ya existia $ID"
  EST=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$ID" \
         --query 'Reservations[0].Instances[0].State.Name' --output text)
  if [ "$EST" = "stopped" ]; then
    aws ec2 start-instances --region "$REGION" --instance-ids "$ID" >/dev/null
    ok "estaba apagada, arrancando"
  fi
fi

log "Esperando que la instancia este corriendo"
aws ec2 wait instance-running --region "$REGION" --instance-ids "$ID"
IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$ID" \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
ok "corriendo, IP publica $IP"

# ------------------------------------------------------------------ estado
cat > "$ESTADO" <<EOF
# Generado por 01-infra-ev3.sh el $(date -Is)
# La IP cambia en CADA arranque de la instancia: no la escribas en ningun codigo.
CUENTA=$CUENTA
REGION=$REGION
VPC=$VPC
SUBNET=$SUBNET
SG=$SG
INSTANCIA=$ID
IP=$IP
EOF
ok "estado guardado en $ESTADO"

log "FASE 1 lista"
cat <<EOF
    instancia : $ID   ($TIPO, 4 GB)
    IP        : $IP
    SSM       : aws ssm start-session --target $ID --region $REGION

    Apagar al terminar de trabajar (esto es lo que consume credito):
      aws ec2 stop-instances --region $REGION --instance-ids $ID

    Sigue la fase 2: subir el compose de la EV3, los microservicios y el front.
EOF
