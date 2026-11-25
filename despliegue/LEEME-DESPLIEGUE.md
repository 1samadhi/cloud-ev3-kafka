# Desplegar en tu propio Learner Lab

Para quien quiera levantar el sistema completo en **su** cuenta de AWS, con sus
credenciales. Escrito después de hacerlo de verdad el 23 de noviembre de 2026,
así que incluye los tropiezos.

> **¿De verdad lo necesitás?** Si sólo querés desarrollar, `docker compose up -d
> --build` en tu máquina te da los 21 contenedores y no gastás un peso de
> crédito. Y si querés probar contra lo que ya está desplegado, apuntá tu front
> al API Gateway existente con una variable de entorno. Desplegar tiene sentido
> si querés tu propia copia en la nube o un respaldo por si el otro lab muere.

---

## Lo que vas a gastar

| | |
|---|---|
| Instancia | `t3.large` — hacen falta ~4,6 GB de RAM y `t3.medium` (4 GB) **no alcanza** |
| Costo | ~US$2 por día encendida |
| Disco | 50 GB (con 30 queda muy justo: son 9 imágenes Java más las capas del build) |
| Tiempo | 40 a 60 minutos, casi todo esperando el build |

Apagá la instancia cuando no la uses. Con la Elastic IP la dirección **no
cambia** al apagar y prender, así que no rompe nada.

---

## Antes de empezar

```bash
# 1. Tus credenciales del lab, pegadas en ~/.aws/credentials
#    (AWS Details -> AWS CLI -> Show). Vencen en 1 hora.
# 2. Comprobá que funcionan:
aws sts get-caller-identity
```

Si te dice `ExpiredToken` a mitad del proceso, volvé a pegarlas y seguí: los
scripts leen el estado de un archivo, no de la memoria.

---

## El orden

```bash
cd despliegue

./01-infra-ev3.sh              # VPC, grupo de seguridad, EC2, y genera
                               # .ev3-estado.env con TUS ids
./09-ip-fija-ev3.sh            # Elastic IP: que la direccion no cambie
./00-preparar-entorno.sh       # buildx 0.19 + Python 3.11 + hop limit 2
./11-arquitectura-nueva.sh     # clona los 3 repos, compila, levanta los 21
                               # contenedores y corre la prueba de humo
./12-gateway-arquitectura-nueva.sh   # API Gateway: abre puertos y crea rutas
./13-reparar-respuestas.sh           # las respuestas de integracion + verifica
```

Después del `11`, dentro de la instancia:

```bash
cp despliegue/plantilla-compose-override.yml docker-compose.override.yml
# reemplazar TU_IP_PUBLICA por la IP real, y:
docker compose up -d
```

**`.ev3-estado.env` no está en git a propósito**: guarda los IDs de una cuenta
concreta. El `01` genera el tuyo.

---

## Las seis trampas

Cada una me costó tiempo. En orden de aparición:

### 1. `compose build requires buildx 0.17.0 or later`

La AMI trae buildx 0.12. El build **no construye nada** y el mensaje no dice qué
hacer. Lo arregla `00-preparar-entorno.sh`.

### 2. `TypeError: unsupported operand type(s) for |: 'type' and 'NoneType'`

Amazon Linux 2023 trae Python 3.9 y la prueba de humo usa `str | None`, que es
3.10+. Correrla con `python3.11`.

### 3. `Bind for 0.0.0.0:3306 failed: port is already allocated`

Un contenedor viejo creado con `docker run` suelto (no con compose) sobrevive a
`docker compose down`. Y como todo depende de MySQL, arrastra a los otros 20.

```bash
docker ps -a                   # mirá los que no son del compose
docker rm -f <nombre>
```

### 4. El handshake del WebSocket da 403 desde tu propio front

`CORS_ORIGENES` está fijado a localhost en el compose. Lo corrige el override.

### 5. `Invalid mapping expression specified: *`

Al crear rutas del API Gateway, el valor de una cabecera estática **debe pasarse
por archivo JSON**:

```bash
echo '{"method.response.header.Access-Control-Allow-Origin": "'"'"'*'"'"'"}' > cors.json
aws apigateway put-integration-response ... --response-parameters file://cors.json
```

Con la forma `clave=valor` la CLI se come las comillas simples y rechaza el
mapeo. Si falta la respuesta de integración, la ruta devuelve:

```
Execution failed due to configuration error:
  No match for output mapping and no default output mapping configured
```

### 6. Una ruta recién publicada da 403 o 500

Es **propagación del stage**: tarda hasta un minuto. Para saber si el problema es
la configuración o sólo el tiempo:

```bash
aws apigateway test-invoke-method --rest-api-id ... --resource-id ... \
  --http-method GET --path-with-query-string "/v1/productos" --query log
```

`test-invoke-method` prueba la configuración **sin pasar por el stage**. Si ahí da
200, esperá y reintentá.

---

## Cómo saber si quedó bien

```bash
# 21 contenedores, todos healthy
docker ps --format '{{.Names}}\t{{.Status}}' | sort

# las 25 comprobaciones del flujo completo
python3.11 infra/prueba-humo.py        # tiene que decir "TODO OK"

# las rutas del gateway, desde tu máquina
curl -s "$API_URL/v1/productos" | head -c 200
```

Y la prueba de que DynamoDB es el real, no el emulador:

```bash
aws dynamodb list-tables --region us-east-1
# HistorialEventos  RecomendacionesUsuario  EventosOperaciones-desarrollo
```

---

## Si algo sale mal

| Síntoma | Qué pasó |
|---|---|
| `ExpiredToken` | credenciales vencidas. Pegá las nuevas y seguí donde ibas |
| un servicio en `unhealthy` | `docker logs <nombre> --tail 50`. Casi siempre es que MySQL o Kafka no estaban listos |
| el build se queda "colgado" | es normal: compila 7 servicios Java y 2 frontends. Entre 20 y 40 minutos |
| `dubious ownership in repository` | `git config --global --add safe.directory /home/ec2-user/proyecto` |
| una ruta da 504 | el puerto no está abierto en el grupo de seguridad (los nuevos son 8086-8091) |
