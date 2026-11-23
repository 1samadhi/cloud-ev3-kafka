#!/usr/bin/env bash
# =============================================================================
#  09-ip-fija-ev3.sh — Reserva una Elastic IP y la asocia a la instancia
#
#  PROBLEMA QUE RESUELVE: la IP publica cambia en CADA arranque de la instancia.
#  Eso obliga a regenerar, una y otra vez:
#     - infra/.env               (advertised.listeners de Kafka)
#     - las unidades systemd     (bootstrap y CORS)
#     - las NUEVE integraciones de API Gateway, que guardan la IP del backend
#  El tercero es el caro: rehacer nueve rutas a mano en cada reinicio es la
#  clase de tarea que se olvida justo el dia de la presentacion.
#
#  COSTO: una Elastic IP ASOCIADA a una instancia encendida es gratis. Con la
#  instancia apagada cuesta ~US$0,12 por dia. Apagando todas las noches hasta
#  fin de mes es menos de US$1 de los 50 del lab: barato por quitar de encima
#  el paso mas fragil del despliegue.
#
#  Uso:  ./09-ip-fija-ev3.sh
# =============================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ESTADO="$AQUI/.ev3-estado.env"
[ -f "$ESTADO" ] || { echo "falta $ESTADO"; exit 1; }
# shellcheck disable=SC1090
. "$ESTADO"
export AWS_PAGER=""

ETIQUETA="pedidos360-ev3"
log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '    \033[1;32mOK\033[0m %s\n' "$*"; }

# ------------------------------------------------- reservar (o reutilizar)
log "Elastic IP"
ALLOC=$(aws ec2 describe-addresses --region "$REGION" \
         --filters "Name=tag:Name,Values=$ETIQUETA" \
         --query 'Addresses[0].AllocationId' --output text 2>/dev/null || echo None)

if [ "$ALLOC" = "None" ] || [ -z "$ALLOC" ]; then
  ALLOC=$(aws ec2 allocate-address --region "$REGION" --domain vpc \
           --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$ETIQUETA}]" \
           --query AllocationId --output text)
  ok "reservada $ALLOC"
else
  ok "ya existia $ALLOC"
fi

IP_FIJA=$(aws ec2 describe-addresses --region "$REGION" --allocation-ids "$ALLOC" \
           --query 'Addresses[0].PublicIp' --output text)
ok "direccion: $IP_FIJA"

# ------------------------------------------------- asociar a la instancia
log "Asociando a la instancia $INSTANCIA"
EST=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
       --query 'Reservations[0].Instances[0].State.Name' --output text)
[ "$EST" = "running" ] || { echo "    la instancia esta '$EST'; arrancala primero"; exit 1; }

aws ec2 associate-address --region "$REGION" --instance-id "$INSTANCIA" \
  --allocation-id "$ALLOC" --query AssociationId --output text >/dev/null
sleep 6

IP_ACTUAL=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCIA" \
             --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
ok "la instancia responde ahora en $IP_ACTUAL"
[ "$IP_ACTUAL" = "$IP_FIJA" ] || echo "    AVISO: no coincide con la EIP reservada"

# guardar
sed -i "s|^IP=.*|IP=$IP_FIJA|" "$ESTADO"
grep -q '^EIP_ALLOC=' "$ESTADO" && sed -i "s|^EIP_ALLOC=.*|EIP_ALLOC=$ALLOC|" "$ESTADO" \
                                || echo "EIP_ALLOC=$ALLOC" >> "$ESTADO"
grep -q '^IP_FIJA=' "$ESTADO" && sed -i "s|^IP_FIJA=.*|IP_FIJA=$IP_FIJA|" "$ESTADO" \
                              || echo "IP_FIJA=$IP_FIJA" >> "$ESTADO"

cat <<EOF

    ================================================================
    IP FIJA: $IP_FIJA
    ================================================================

    Ya no cambia aunque apagues y prendas la instancia.

    AHORA hay que reapuntar todo a esta direccion, UNA sola vez:
       ./06-reanudar-ev3.sh      (Kafka + systemd + front)
       ./08-apigateway-ev3.sh    (las nueve integraciones)

    Al terminar de trabajar:
       aws ec2 stop-instances --region $REGION --instance-ids $INSTANCIA

    (la EIP queda reservada; cuesta ~US\$0,12/dia mientras este apagada)
EOF
