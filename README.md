# Pedidos360 — Evaluación 3 (DSY1107)

Backend de streaming con Apache Kafka: clúster de 3 brokers con replicación y tres
microservicios sobre dos tópicos.

El frontend está en un repositorio aparte: [cloud-ev3-front](https://github.com/1samadhi/cloud-ev3-front)

## Componentes

```
docker-compose.yml     Stack completo: clúster, MySQL, microservicios y consola
infra/                 Clúster Kafka solo: 3 Zookeeper + 3 brokers + Kafka UI
infra/terraform-telemetria/  DynamoDB + Streams + Lambdas + API Gateway WebSocket
ms-pago/               Pagos con Webpay Plus y detección de fraude  :8083
ms-recomendaciones/    Recomendaciones por clickstream              :8084
ms-kafka-admin/        Administración y supervisión del clúster     :8085
lambda-telemetria/     Las dos Lambdas del dashboard en tiempo real
```

| Servicio | Puerto | Tópico | Grupo de consumo |
|---|---|---|---|
| ms-pago | 8083 | `payment-attempts` | `payment-velocity-group` |
| ms-recomendaciones | 8084 | `interacciones-user` | `recommendation-consumer-group` |
| ms-kafka-admin | 8085 | — | — |

Stack: Java 21 · Spring Boot 3.5.6 · Apache Kafka 7.5.0 · MySQL 8.0 · SDK Transbank 6.2.1

## Arranque

### Todo el sistema con un comando

```bash
cp .env.example .env     # ajustar KAFKA_HOST si no es local
docker compose up -d --build
```

El `docker-compose.yml` de la raíz levanta el stack completo y en orden, esperando
a que cada capa esté **sana** antes de arrancar la siguiente:

```
zookeeper1..3 → kafka1..3 → creación de tópicos → microservicios → consola
mysql ─────────────────────────────────────────┘
```

| Servicio | URL |
|---|---|
| Consola (frontend) | http://localhost:8090 |
| ms-pago | http://localhost:8083 |
| ms-recomendaciones | http://localhost:8084 |
| ms-kafka-admin | http://localhost:8085 |
| Kafka UI | http://localhost:8080 |

Tres detalles del compose que no son evidentes:

- **Los tópicos los crea un contenedor de un solo uso** (`crear-topicos`), no
  `auto.create.topics`: la rúbrica pide particiones, factor de replicación,
  retención y `min.insync.replicas` explícitos, y la creación automática los
  haría con los defaults del broker. Lleva `--if-not-exists`, así que `up` dos
  veces no falla, y los microservicios esperan su
  `service_completed_successfully`.
- **Dentro de Docker los servicios usan `kafkaN:29092`**, el listener INTERNO.
  El `9092` del host no existe en la red de contenedores.
- **Las URL que abre el navegador van con `localhost`**, nunca con el nombre del
  servicio de Docker: `PAGOS_RETURN_URL`, `FRONT_URL` y las tres `API_*` de la
  consola las resuelve el navegador del usuario, no Docker.

El servicio `consola` construye el frontend desde `../cloud-ev3-front`, que se
espera clonado al lado de este repositorio. Si no está, comentá ese servicio: el
backend levanta igual.

Para trabajar **sólo el clúster**, `infra/docker-compose.yml` sigue sirviendo.

#### Validar el compose sin Docker

```bash
python infra/validar-compose.py docker-compose.yml
```

Comprueba que el YAML parsee, que las anclas se mezclen, que todo `depends_on`
apunte a un servicio existente, que los microservicios usen el listener interno
y que los orígenes de CORS coincidan con el puerto en el que se publica la
consola. No reemplaza a `docker compose config`: no valida imágenes ni contextos
de build.

### Arranque manual, paso a paso

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
POST     /api/pagos/iniciar       { clientId, amount, transactionId }
POST|GET /api/pagos/commit        vuelta de Transbank: confirma y redirige al frontend
GET      /api/alertas             bloqueos vigentes y clientes vigilados
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

### Vuelta desde Webpay

Transbank devuelve al navegador haciendo un **POST** con `token_ws` al `returnUrl`. Una página
estática no puede leer el cuerpo de un POST, así que el `returnUrl` apunta a este mismo
servicio y no al frontend:

```
Webpay --POST token_ws--> :8083/api/pagos/commit --302--> <frontend>/callback?estado=...
```

`commit` confirma la transacción, actualiza el estado del cliente y recién entonces redirige
al frontend por GET con el resultado en el query string (`estado`, `orden`, `cliente`, `monto`,
`autorizacion`, `tarjeta`, `fecha`). Cuando el cliente abandona el formulario, Transbank no
envía `token_ws` sino `TBK_TOKEN`: ese caso se trata como anulación, no como error.

Las dos URL se parametrizan por entorno:

| Variable | Default | Qué es |
|---|---|---|
| `PAGOS_RETURN_URL` | `http://localhost:8083/api/pagos/commit` | lo que recibe Transbank |
| `FRONT_URL` | `http://localhost:5173` | base del frontend al que se redirige |

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

## Salud de los servicios

Los tres exponen `/actuator/health`, y **sólo** ese endpoint de Actuator: el
resto (`env`, `beans`, `configprops`, `heapdump`) filtra configuración y
credenciales sin pedir autenticación, y acá no hay Spring Security que los
cubra.

```bash
curl -s http://localhost:8085/actuator/health | jq
```

```json
{
  "status": "UP",
  "components": {
    "diskSpace": { "status": "UP" },
    "kafkaCluster": {
      "status": "UP",
      "details": { "clusterId": "...", "brokers": 3, "brokersMinimos": 2, "controlador": 1 }
    }
  }
}
```

Para qué sirve, concretamente:

- **`HEALTHCHECK` de cada imagen** — antes no había, así que el contenedor se
  reportaba sano apenas arrancaba el proceso.
- **`depends_on: condition: service_healthy`** del compose, que es lo que evita
  que un microservicio arranque contra una base que todavía no acepta
  conexiones.
- **El indicador de la consola**, que dejó de sondear endpoints de negocio.

**Spring Boot no trae un indicador de salud para Kafka**: de fábrica `health`
mira la aplicación, el disco y la base. En un proyecto de streaming eso deja
fuera justo la dependencia que importa, así que `ms-kafka-admin` agrega
`KafkaClusterHealthIndicator` con el `AdminClient` que ya tenía.

Ese indicador reporta **DOWN con menos de 2 brokers vivos**, no con cero: con un
solo broker el clúster no puede honrar el `min.insync.replicas=2` con el que se
crearon los tópicos, y una escritura con `acks=all` empezaría a fallar. Reportar
UP en ese estado sería mentirle al healthcheck.

## Telemetría en tiempo real — Lambdas y API Gateway

La parte del sistema que vive en AWS. El diseño está explicado en
`ARQUITECTURA-PEDIDOS360.md`; acá está cómo desplegarlo.

```
escritura en DynamoDB → Streams → Lambda Procesador → API Gateway → navegador
                                        ↑
   $connect / $disconnect → Lambda Conexiones → tabla ConexionesWebSocket
```

### Las dos funciones

Un solo jar (`lambda-telemetria/`) con dos handlers: comparten el modelo del
evento y la mitad de las dependencias, así que separarlos duplicaría 11 MB sin
ganar nada.

| Función | Handler | Qué hace |
|---|---|---|
| Conexiones | `ConexionesHandler` | registra y borra conexiones en `$connect` / `$disconnect` |
| Procesador | `ProcesadorHandler` | recibe el lote del stream y lo difunde a todos los conectados |

Detalles que importan:

- **El jar no lleva Spring.** Una Lambda no necesita un contexto de Spring, y
  arrancar uno en cada invocación multiplicaría el arranque en frío, que es lo
  único delicado de Java sobre Lambda.
- **Netty y Apache HttpClient quedan excluidos** del SDK; se usa
  `url-connection-client`, que va sobre la JDK. El jar baja de 16 MB a 11 MB, y
  en Lambda el tamaño del artefacto se paga en cada arranque en frío.
- **Se difunde un mensaje con el lote completo**, no uno por evento: con la
  ventana de 1 segundo del trigger los eventos llegan agrupados, y
  `postToConnection` se cobra por mensaje. El dashboard ya espera un arreglo.
- **El 410 Gone borra la conexión.** Cuando un navegador se va sin que
  `$disconnect` alcance a limpiar, API Gateway responde 410; sin borrar ahí, la
  tabla acumula conexiones muertas a las que se intenta escribir para siempre.
- **El endpoint de administración va en `https://`** aunque el navegador use
  `wss://`. Es la misma API vista desde el otro lado, y confundirlos es el error
  clásico de este montaje.

### Despliegue

```bash
cd lambda-telemetria && mvn clean package     # genera el jar sombreado
cd ../infra/terraform-telemetria
terraform init
terraform apply
```

La salida `url_websocket` es lo que se pega en el campo *Endpoint WebSocket* de
la consola, o lo que se pasa como `WS_URL` al contenedor del frontend.

Para comprobar el pipeline de punta a punta, con la consola conectada:

```bash
aws dynamodb put-item --table-name EventosOperaciones-desarrollo \
  --item file://evento-prueba.json --region us-east-1
```

El evento debería aparecer en el tablero al instante.

### Decisiones de la infraestructura

| Decisión | Por qué |
|---|---|
| `NEW_IMAGE` en el stream | al dashboard sólo le interesa el dato nuevo; `NEW_AND_OLD_IMAGES` duplicaría el tamaño de cada registro |
| `batch_size` 50 + ventana de 1 s | el lote es un techo; lo que agrupa de verdad es la ventana, imperceptible en un tablero |
| `LATEST` | al desplegar no se reproduce todo el historial del stream |
| `maximum_retry_attempts` 2 | un evento que no se puede difundir no debe bloquear el shard y detener la telemetría |
| `PAY_PER_REQUEST` | la carga de una demostración es a ráfagas; con capacidad fija hay que elegir entre pagar de más o recibir throttling |
| TTL en las dos tablas | la telemetría se mira en vivo y después no sirve; las conexiones muertas se barren solas |
| Dos roles IAM | cada función recibe sólo lo que necesita, y se ve en la consola quién puede qué |
| `execute-api:ManageConnections` acotado a la etapa | con `*`, cualquier Lambda de la cuenta podría escribir a cualquier WebSocket |

### Lo que falta para que salga del flujo real

Las Lambdas difunden lo que haya en la tabla, pero **ningún microservicio
escribe todavía en `EventosOperaciones`**. Hoy se alimenta a mano con el
`put-item` de arriba.

Para cerrarlo, `ms-recomendaciones` y `ms-pago` tendrían que escribir su evento
en DynamoDB además de publicarlo en Kafka: en `InteraccionService` después de
producir la interacción, y en `ConsumidorComponent` cuando el antifraude marca
un bloqueo. Son dos puntos concretos, y requieren credenciales de AWS en los
servicios.

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

**CORS declara orígenes concretos, no comodín.** `CORS_ORIGENES` acepta una lista separada por
comas y viene por defecto con `http://localhost:5173` (Vite en desarrollo) y
`http://localhost:8090` (la imagen nginx del frontend). Un origen que no esté en la lista recibe
el bloqueo del navegador aunque el servicio responda.

El mapeo cubre `/api/**` y también `/actuator/**`, porque la consola lee
`/actuator/health` desde el navegador: sin ese segundo mapeo mostraría los tres
servicios como caídos estando arriba.
