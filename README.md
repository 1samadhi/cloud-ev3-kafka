# Pedidos360 — Evaluación 3 (DSY1107)

Backend de streaming con Apache Kafka: clúster de 3 brokers con replicación y tres
microservicios sobre dos tópicos.

El frontend está en un repositorio aparte: [cloud-ev3-front](https://github.com/1samadhi/cloud-ev3-front)

## Componentes

```
infra/                 Clúster Kafka: 3 Zookeeper + 3 brokers + Kafka UI
ms-pago/               Pagos con Webpay Plus y detección de fraude  :8083
ms-recomendaciones/    Recomendaciones por clickstream              :8084
ms-kafka-admin/        Administración y supervisión del clúster     :8085
```

| Servicio | Puerto | Tópico | Grupo de consumo |
|---|---|---|---|
| ms-pago | 8083 | `payment-attempts` | `payment-velocity-group` |
| ms-recomendaciones | 8084 | `interacciones-user` | `recommendation-consumer-group` |
| ms-kafka-admin | 8085 | — | — |

Stack: Java 21 · Spring Boot 3.5.6 · Apache Kafka 7.5.0 · MySQL 8.0 · SDK Transbank 6.2.1

## Arranque

### 1. Clúster Kafka

```bash
cd infra
cp .env.example .env     # ajustar KAFKA_HOST
docker compose up -d
```

Queda disponible:

- brokers desde el host: `localhost:9092`, `localhost:9093`, `localhost:9094`
- brokers entre contenedores: `kafka1:29092`, `kafka2:29092`, `kafka3:29092`
- Kafka UI: http://localhost:8080

El mismo archivo sirve en local y en una instancia EC2: lo único que cambia es `KAFKA_HOST`
en el `.env`. Esa variable alimenta `advertised.listeners`, que es la dirección que el broker
le responde al cliente cuando se conecta; si anuncia `localhost` a un cliente remoto, el
cliente intentará conectarse a sí mismo.

### 2. Tópicos

```bash
docker exec kafka1 kafka-topics --bootstrap-server kafka1:29092 \
  --create --topic payment-attempts --partitions 3 --replication-factor 3 \
  --config retention.ms=604800000 --config cleanup.policy=delete --config min.insync.replicas=2

docker exec kafka1 kafka-topics --bootstrap-server kafka1:29092 \
  --create --topic interacciones-user --partitions 3 --replication-factor 3 \
  --config retention.ms=604800000 --config cleanup.policy=delete --config min.insync.replicas=2
```

O desde el microservicio administrador, una vez levantado:

```bash
curl -X POST http://localhost:8085/api/topicos -H 'Content-Type: application/json' \
  -d '{"nombre":"payment-attempts","particiones":3,"replicas":3}'
```

### 3. MySQL

```bash
cd ms-pago && docker compose up -d mysql-db
```

Base `pagos_db`, usada por `ms-pago` y `ms-recomendaciones`.

### 4. Microservicios

```bash
cd ms-pago            && mvn -B package -DskipTests
cd ms-recomendaciones && mvn -B package -DskipTests
cd ms-kafka-admin     && mvn -B package -DskipTests

java -jar ms-pago/target/*.jar --server.port=8083 \
  --spring.kafka.bootstrap-servers='localhost:9092,localhost:9093,localhost:9094'
java -jar ms-recomendaciones/target/*.jar --server.port=8084 \
  --spring.kafka.bootstrap-servers='localhost:9092,localhost:9093,localhost:9094'
java -jar ms-kafka-admin/target/*.jar --server.port=8085 \
  --spring.kafka.bootstrap-servers='localhost:9092,localhost:9093,localhost:9094'
```

## ms-pago — Webpay y antifraude

```
POST /api/pagos/iniciar          { clientId, amount, transactionId }
POST /api/pagos/commit?token_ws= confirma la transacción en Transbank
```

El flujo consulta primero si el cliente está bloqueado; si no lo está, publica el intento al
tópico y pide el token a Transbank. Un componente consumidor evalúa la regla de velocidad de
forma asíncrona: **3 o más intentos del mismo cliente en menos de 10 segundos bloquean la cuenta
por 30 minutos**.

La regla se evalúa por `clientId` y no por número de tarjeta, porque el PAN nunca llega al
backend: lo captura Transbank en su propio formulario.

Respuestas:

| Código | Situación |
|---|---|
| 200 | transacción creada, devuelve `url` y `token` de Webpay |
| 400 | payload inválido |
| 429 | cliente bloqueado por la regla de velocidad |

Probar el bloqueo:

```bash
for i in 1 2 3 4; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8083/api/pagos/iniciar \
    -H 'Content-Type: application/json' \
    -d "{\"clientId\":\"demo\",\"amount\":15000,\"transactionId\":\"TX-$i\"}"
done
# 200 200 200 429
```

### Tarjetas de prueba

Ambiente de integración de Transbank, sin dinero real.

| Tarjeta | Número | CVV | Resultado |
|---|---|---|---|
| VISA | 4051 8856 0044 6623 | 123 | aprobada |
| MASTERCARD | 5186 0595 5959 0568 | 123 | rechazada |

RUT `11.111.111-1`, clave `123`, cualquier fecha futura.

La redirección a Webpay debe hacerse por **POST** con el campo `token_ws` en un formulario.
Un `GET` es rechazado por Transbank.

## ms-recomendaciones — clickstream

```
POST /api/interacciones              { clientId, itemId, action, category, productType }
GET  /api/recomendaciones?userId=    Top 5 enriquecido con el catálogo
```

Pesos por tipo de interacción: `view` 1 · `cart` 2 · `purchase` 3.

El controlador sólo publica el evento a Kafka; el consumidor es el único que persiste y
recalcula el ranking. Mantener la escritura en un solo lugar evita duplicar las filas y, con
ellas, los puntajes.

```bash
curl -X POST http://localhost:8084/api/interacciones -H 'Content-Type: application/json' \
  -d '{"clientId":"user_1","itemId":"item_116","action":"purchase","category":"hogar","productType":"electrodomesticos"}'

curl "http://localhost:8084/api/recomendaciones?userId=user_1"
```

## ms-kafka-admin — administración y supervisión

Opera sobre el clúster con el `AdminClient` del cliente oficial de Kafka.

```
GET    /api/cluster                           brokers y controlador
GET    /api/topicos                           lista con particiones, réplicas e ISR
GET    /api/topicos/{nombre}                  configuración efectiva y detalle por partición
POST   /api/topicos                           crea un tópico
DELETE /api/topicos/{nombre}                  elimina un tópico
GET    /api/consumer-groups                   grupos, estado y miembros
GET    /api/consumer-groups/{id}/lag          offsets y desfase por partición
```

La creación valida que el factor de replicación no supere la cantidad de brokers vivos y
responde con el número disponible, en lugar de dejar escapar el error de Kafka.

El borrado rechaza los tópicos internos y los tres tópicos en uso del proyecto.

```bash
curl -s http://localhost:8085/api/consumer-groups/recommendation-consumer-group/lag
```

## Configuración de Kafka

Decisiones explícitas del clúster:

| Parámetro | Valor | Motivo |
|---|---|---|
| particiones | 3 | permite paralelizar el consumo dentro de un grupo |
| factor de replicación | 3 | cada partición tiene copia completa en los tres brokers |
| `min.insync.replicas` | 2 | tolera la caída de un broker sin perder escrituras |
| `retention.ms` | 604800000 (7 días) | ventana suficiente para reprocesar |
| `cleanup.policy` | delete | son eventos, no estado compactable por clave |
| `segment.bytes` | 268435456 (256 MB) | limita el tamaño de los segmentos en disco |
| heap por broker | 512 MB | seis JVM conviven en un equipo de desarrollo |

La clave de cada mensaje es el identificador de la entidad (`clientId`): Kafka garantiza orden
sólo dentro de una partición, así que usar el cliente como clave asegura que sus eventos lleguen
ordenados al mismo consumidor. Esto es lo que permite que la ventana de 10 segundos del
antifraude cuente correctamente.

## Notas de configuración

**Dirección de los brokers según dónde corre el cliente:**

| Desde | Dirección |
|---|---|
| host | `localhost:9092,localhost:9093,localhost:9094` |
| contenedor en la red del clúster | `kafka1:29092` |
| fuera de AWS hacia una EC2 | `<ip-pública>:9092` |

**`bootstrap.servers` va sin esquema.** Con `http://` Kafka falla con
`Invalid url in bootstrap.servers`.

**Healthcheck de Zookeeper con `srvr`, no con `ruok`.** Desde Zookeeper 3.5 los comandos de
cuatro letras están fuera de la lista permitida y `ruok` responde que no está habilitado.
`srvr` sí está permitido y además informa el rol en el quórum, que sólo aparece cuando el nodo
está sincronizado.

**Las credenciales no van en `application.yaml`.** Se leen de variables de entorno, porque ese
archivo se versiona.
