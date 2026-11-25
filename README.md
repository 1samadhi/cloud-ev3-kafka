# Pedidos360 — Evaluación 3 (DSY1107)

Backend de la tienda Pedidos360: **siete microservicios Spring Boot** que se comunican por
**Kafka** (eventos analíticos), **RabbitMQ** (notificaciones) y REST (comandos), con un
clúster Kafka de 3 brokers, un clúster RabbitMQ de 3 nodos, MySQL y DynamoDB.

| Repositorio | Qué es |
|---|---|
| **cloud-ev3-kafka** (este) | microservicios, infraestructura, compose, Lambdas |
| [cloud-ev3-front](https://github.com/1samadhi/cloud-ev3-front) | consola de operaciones (React) |
| [pedidos360-tienda](https://github.com/DiVilota/pedidos360-tienda) | tienda para el cliente (React) |

Documentos que conviene leer, en este orden:

1. [`ARQUITECTURA-PEDIDOS360.md`](ARQUITECTURA-PEDIDOS360.md) — el por qué de cada tecnología.
2. [`PLAN-INTEGRACION-EV3.md`](PLAN-INTEGRACION-EV3.md) — el contrato: tópicos, grupos, DTOs,
   colas, puertos y API de cada servicio, mapeado a la pauta de evaluación.
3. [`docs/trabajo/`](docs/trabajo/README.md) — un documento por tarea, con lo que se verificó y
   lo que no.
4. [`docs/trabajo/PENDIENTES.md`](docs/trabajo/PENDIENTES.md) — lo que falta, listo para tomar.
5. [`CHANGELOG.md`](CHANGELOG.md) — versiones.

## Componentes

```
docker-compose.yml           Sistema completo: 22 contenedores
infra/kafka/                 crear-topicos.sh: los 4 tópicos y sus DLT, configurados por flujo
infra/rabbitmq/              Clúster RabbitMQ de 3 nodos (también standalone)
infra/docker-compose.yml     Clúster Kafka solo: 3 Zookeeper + 3 brokers + Kafka UI
infra/terraform-telemetria/  DynamoDB + Streams + Lambdas + API Gateway WebSocket
infra/prueba-humo.py         Recorre el flujo de compra contra el sistema levantado
infra/validar-compose.py     Chequeos estáticos del compose (sin Docker)
despliegue/                  Scripts de despliegue en AWS por SSM (arquitectura anterior, ver PENDIENTES)
lambda-telemetria/           Las dos Lambdas del dashboard en tiempo real
ms-*/                        Un microservicio por carpeta, cada uno con pom.xml y Dockerfile
```

| Servicio | Puerto | Kafka produce | Kafka consume (grupo) | RabbitMQ | Persistencia |
|---|---|---|---|---|---|
| ms-pago | 8083 | `payment-attempts`, `pagos-resultado` | `payment-attempts` (`grupo-antifraude`) | — | MySQL `pagos_db` |
| ms-recomendaciones | 8084 | `interacciones-user` (simulador) | `interacciones-user` (`grupo-recomendaciones`, `grupo-dashboard`), `catalogo-productos` (`grupo-catalogo-recomendaciones`) | — | DynamoDB |
| ms-kafka-admin | 8085 | — | — (AdminClient) | — | — |
| ms-productos | 8086 | `interacciones-user` (VER_PRODUCTO), `catalogo-productos` | — | — | MySQL `productos_db` |
| ms-carrito | 8087 | `interacciones-user` (carrito, cupón, envío, compra) | `pagos-resultado` (`grupo-carrito`) | produce | MySQL `carrito_db` |
| ms-notificaciones | 8088 | — | — | consume, DLQ | MySQL `notificaciones_db` |
| ms-rabbit-admin | 8089 | — | — | AmqpAdmin + management | — |

Cada consumidor tiene además su grupo `grupo-dlt-<servicio>` que registra los mensajes muertos.

Stack: Java 21 · Spring Boot 3.5.6 · Confluent Kafka 7.5.0 · RabbitMQ 3.13 · MySQL 8.0 ·
DynamoDB (SDK v2) · SDK Transbank 6.x

### Los tres canales y por qué cada uno

| Canal | Para qué | Por qué ese |
|---|---|---|
| REST | comandos que necesitan respuesta: reservar stock, crear pedido, iniciar pago | el cliente tiene que saber en el momento si hay stock |
| **Kafka** | eventos analíticos: navegación, intentos de pago, resultados, catálogo | log persistente con *replay* y *fan-out*: varios grupos leen lo mismo con offsets independientes |
| **RabbitMQ** | notificaciones al cliente | entrega por mensaje con confirmación, reintentos y DLQ |

Son **dominios de falla independientes**: si RabbitMQ o un consumidor cae, la compra se
completa igual y los mensajes esperan en la cola (verificado, ver doc 17).

## Arranque

### Todo el sistema

Los frontends son repos aparte: clonarlos **al lado** de este y apuntar `.env` a ellos.

```bash
cp .env.example .env          # CONSOLA_DIR y TIENDA_DIR: rutas a los otros dos repos
docker compose up -d --build
python infra/prueba-humo.py   # 25 comprobaciones contra los servicios reales
```

El primer build tarda (compila siete servicios y dos frontends). El sistema usa ~4,6 GB de RAM.

| Qué | URL |
|---|---|
| Tienda | http://localhost:8091 |
| Consola de operaciones | http://localhost:8090 |
| Kafka UI | http://localhost:8080 |
| RabbitMQ management | http://localhost:15672 (`pedidos360` / `pedidos360`) |
| Microservicios | http://localhost:8083 … 8089 |

Orden de arranque, cada capa esperando la **salud** de la anterior:

```
zookeeper1..3 → kafka1..3 → crear-topicos → kafka-ui
rabbitmq1..3 ───────────────────────┐
mysql · dynamodb-local ─────────────┴→ microservicios → tienda · consola
```

Detalles que no son evidentes:

- **Los tópicos los crea `crear-topicos`** ejecutando `infra/kafka/crear-topicos.sh`, no
  `auto.create.topics`: la pauta pide particiones, réplicas, retención y cleanup **por flujo**,
  y la creación automática usaría los defaults del broker. Es idempotente.
- **Dentro de Docker se usa `kafkaN:29092`** (listener interno). El `9092` del host no existe en
  la red de contenedores.
- **Lo que abre el navegador va con `localhost`** (`FRONT_URL`, `FRONT_URL_TIENDA`,
  `PAGOS_RETURN_URL`, las `API_*` de los frontends), nunca con nombres de servicio de Docker.
- **Una base por servicio** en la misma instancia MySQL: no se comparten tablas entre servicios.

Para trabajar sólo un clúster: `infra/docker-compose.yml` (Kafka) o
`infra/rabbitmq/docker-compose.yml` (RabbitMQ).

### Apagar

```bash
docker compose down        # conserva los datos
docker compose down -v     # borra también volúmenes (bases, colas, tópicos)
```

### Compilar y probar sin Docker

Cada servicio tiene pruebas que no necesitan infraestructura (Kafka embebido, H2, mocks):

```bash
cd ms-pago && mvn -B test
```

| Servicio | Pruebas |
|---|---|
| ms-pago | 53 |
| ms-recomendaciones | 39 |
| ms-kafka-admin | 70 |
| ms-productos | 46 |
| ms-carrito | 74 |
| ms-notificaciones | 31 |
| ms-rabbit-admin | 93 |

Con **JDK 23 o superior** los `pom.xml` ya fijan Lombok 1.18.42 y `<proc>full</proc>`; sin eso
el procesador de anotaciones no corre y aparecen cientos de "cannot find symbol". En Windows
con git-bash, invocar Maven como `cmd.exe /c mvn.cmd …`: el script POSIX falla bajo MSYS.

## API por servicio

### ms-pago — Webpay, pasarela simulada y antifraude

```
POST     /api/pagos/iniciar   { clientId, amount, transactionId, pasarela?, tarjeta?, origen? }
POST|GET /api/pagos/commit    vuelta de Transbank: confirma, publica el resultado y redirige
GET      /api/alertas         bloqueos vigentes y alertas de fraude
```

**Regla antifraude** (enunciado: *la misma tarjeta, más de 3 veces en 10 segundos*): ventana
deslizante real sobre la **huella SHA-256 de la tarjeta** (con *pepper*; el número nunca se
guarda, publica ni loguea). El **4.º** intento dentro de 10 s bloquea la huella 30 minutos y
registra la alerta.

Con **Webpay**, Transbank no entrega la tarjeta antes del pago (PCI DSS), así que la huella se
calcula sobre la cuenta; con la pasarela **SIMULADA** se calcula sobre la tarjeta real. Misma
ventana, mismo umbral.

| Código | Situación |
|---|---|
| 200 | Webpay: `{ url, token }` · Simulada: `{ estado: AUTORIZADO, idOrden, ultimos4 }` |
| 400 | payload inválido, con el detalle del campo |
| 402 | pago simulado rechazado (tarjetas terminadas en `0002`) |
| 429 | huella bloqueada por el antifraude |
| 503 | Kafka no confirmó el intento |

```bash
for i in 1 2 3 4; do
  curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:8083/api/pagos/iniciar \
    -H 'Content-Type: application/json' \
    -d "{\"clientId\":\"demo\",\"amount\":15000,\"transactionId\":\"TX-$i\",\"pasarela\":\"SIMULADA\",\"tarjeta\":{\"numero\":\"4051885600446623\"}}"
done
# 200 200 200 429
```

#### Tarjetas de prueba (Webpay de integración, sin dinero real)

| Tarjeta | Número | CVV | Resultado |
|---|---|---|---|
| VISA | 4051 8856 0044 6623 | 123 | aprobada |
| MASTERCARD | 5186 0595 5959 0568 | 123 | rechazada |

RUT `11.111.111-1`, clave `123`, cualquier fecha futura. La redirección a Webpay es un **POST**
con `token_ws`; Transbank rechaza un `GET`.

#### Vuelta desde Webpay

Una página estática no puede leer el cuerpo de un POST, así que el `returnUrl` apunta a este
servicio y no al frontend:

```
Webpay --POST token_ws--> :8083/api/pagos/commit --302--> <frontend>/callback?estado=...
                                   └── publica ResultadoPagoDTO en pagos-resultado
```

El frontend de destino sale del `origen` del pago y de una **lista cerrada**
(`TIENDA → FRONT_URL_TIENDA`, `CONSOLA → FRONT_URL`): nunca se redirige a una URL enviada por el
cliente. Una anulación llega con `TBK_TOKEN` y sin `token_ws`, y se trata como `ANULADO`.

| Variable | Default |
|---|---|
| `PAGOS_RETURN_URL` | `http://localhost:8083/api/pagos/commit` |
| `FRONT_URL` (consola) | `http://localhost:5173` |
| `FRONT_URL_TIENDA` | `http://localhost:5174` |
| `WEBPAY_CODIGO_COMERCIO` | `597055555532` (integración) |

### ms-recomendaciones — productos parecidos, DynamoDB y dashboard

```
POST /api/interacciones             formato de la consola { clientId, itemId, action, ... }
POST /api/interacciones/simular     ?cantidad=50&usuarioId= — tráfico para la demo
GET  /api/recomendaciones?userId=   top 5 con nombre, precio y motivo
GET  /api/metricas                  snapshot de las métricas en vivo
WS   /ws/metricas                   el mismo snapshot empujado cada 2 s
```

Perfil del usuario por categoría, subcategoría y marca con decaimiento temporal; recomienda lo
**parecido**, excluye lo comprado y lo agotado, y explica el motivo ("Porque viste WH-1000XM5").
Historial y recomendaciones en **DynamoDB** (Local en el compose, real en el Learner Lab con
`DYNAMODB_ENDPOINT` vacío). El catálogo se reconstruye leyendo el tópico compactado
`catalogo-productos`.

### ms-productos — catálogo y stock

```
GET  /api/productos                 ?categoria=&q=&orden=relevancia|precio_asc|precio_desc
GET  /api/productos/categorias
GET  /api/productos/{id}            con X-Usuario-Id publica VER_PRODUCTO
POST /api/productos/stock/reservas      todo o nada; 409 con los faltantes
POST /api/productos/stock/liberaciones  idempotente por pedido
PUT  /api/productos/{id}/stock
GET  /api/productos/admin/stock
```

### ms-carrito — carrito, cupones, envío y pedidos

```
GET|DELETE  /api/carrito
POST        /api/carrito/items            { productoId, cantidad }
PATCH       /api/carrito/items/{id}       { cantidad }  (0 = quitar)
POST|DELETE /api/carrito/cupon            { codigo }    BIENVENIDA10 · ENVIOGRATIS · GAMER5000
PUT         /api/carrito/envio            { metodo: ENVIO|RETIRO, direccion, comuna, sucursal }
GET         /api/carrito/sucursales
POST        /api/pedidos                  reserva stock y deja el pedido en PENDIENTE_PAGO
GET         /api/pedidos · /api/pedidos/{id}
GET         /api/admin/resumen · /api/admin/pedidos
```

El usuario va en el header `X-Usuario-Id` (hasta que exista Cognito). El pedido pasa a
`PAGADO` o `PAGO_RECHAZADO` cuando llega su resultado por `pagos-resultado`; un proceso
programado avanza los estados de envío y notifica cada cambio.

### ms-notificaciones — consumidor RabbitMQ

```
GET   /api/notificaciones?usuarioId=
GET   /api/notificaciones/stream?usuarioId=    SSE para la campana de la tienda
PATCH /api/notificaciones/{id}/leida
POST  /api/notificaciones/leidas?usuarioId=
GET   /api/notificaciones/fallidas              lo que terminó en la DLQ
```

Topología: exchange topic `pedidos360.notificaciones` → colas **quorum**
`notificaciones.cliente` (`carrito.*`, `pedido.*`, `envio.*`) y `notificaciones.auditoria`
(`#`). Tras 3 reintentos el mensaje va por el DLX `pedidos360.notificaciones.dlx` a
`notificaciones.cliente.dlq`, donde se loguea con sus headers `x-death` y se guarda.

### ms-rabbit-admin — administración de RabbitMQ

```
GET|POST /api/rabbit/colas      DELETE /api/rabbit/colas/{nombre}   POST /api/rabbit/colas/{nombre}/purgar
GET|POST /api/rabbit/exchanges  DELETE /api/rabbit/exchanges/{nombre}
GET|POST|DELETE /api/rabbit/bindings
GET      /api/rabbit/cluster
```

Protege `amq.*` y la topología del sistema salvo `?forzar=true`.

### ms-kafka-admin — administración de Kafka

```
GET    /api/cluster                               brokers y controlador
GET    /api/brokers/{id}/configuracion
GET    /api/topicos · /api/topicos/{nombre}
POST   /api/topicos                               crea, con validación de réplicas vs brokers vivos
DELETE /api/topicos/{nombre}                      protege los del dominio y sus DLT
GET|PUT /api/topicos/{nombre}/configuracion       lista blanca tipada de configs
POST   /api/topicos/{nombre}/particiones          sólo aumentar (Kafka no permite reducir)
GET    /api/consumer-groups · /{id} · /{id}/lag   miembros, asignación y desfase por partición
POST   /api/consumer-groups/{id}/offsets/reset    EARLIEST|LATEST|TIMESTAMP|OFFSET; 409 con miembros activos
DELETE /api/consumer-groups/{id}
```

## Kafka: tópicos por flujo

| Tópico | Part. | Retención | Cleanup | Key | Por qué |
|---|---|---|---|---|---|
| `interacciones-user` | 6 | 7 d | delete + lz4 | usuario | el flujo de más volumen: más paralelismo |
| `payment-attempts` | 3 | 30 d | delete | huella de tarjeta | auditoría antifraude; orden por tarjeta para la ventana |
| `pagos-resultado` | 3 | 90 d | delete | pedido | registro de ventas |
| `catalogo-productos` | 3 | infinita | **compact** | producto | es estado: basta el último snapshot por producto |
| `<tópico>.DLT` ×4 | igual al origen | 14 d | delete | la original | el DLT publica en la misma partición del original |

Todos con factor de replicación 3 y `min.insync.replicas=2`; productores con `acks=all` e
idempotencia. El detalle y el porqué de cada valor están comentados en
`infra/kafka/crear-topicos.sh`.

**Manejo de errores en todos los consumidores:** `ErrorHandlingDeserializer`, reintentos con
espera exponencial para errores transitorios, envío directo al DLT de lo que no tiene arreglo
(JSON roto, payload inválido) y un consumidor del DLT que lo registra. Un mensaje defectuoso
nunca traba su partición.

## Telemetría en AWS — Lambdas y API Gateway

```
ms-recomendaciones → DynamoDB EventosOperaciones → Streams → Lambda Procesador → API Gateway → navegador
                                                                   ↑
                 $connect / $disconnect → Lambda Conexiones → tabla ConexionesWebSocket
```

En local la consola se conecta directo a `ws://localhost:8084/ws/metricas`. En AWS,
`ms-recomendaciones` escribe un snapshot en `EventosOperaciones-<entorno>` cada 5 s si
`DYNAMODB_PUBLICAR_OPERACIONES=true`, y la Lambda lo difunde por API Gateway.

```bash
cd lambda-telemetria && mvn clean package
cd ../infra/terraform-telemetria && terraform init && terraform apply
```

La salida `url_websocket` va en el campo *Endpoint WebSocket* de la consola o en `WS_URL`.
Decisiones de la infraestructura en `docs/trabajo/07-lambdas-telemetria.md`.

> El Terraform crea sus propios roles IAM, y el Learner Lab no lo permite: hay que adaptarlo a
> `LabRole` antes del `apply` (ver PENDIENTES).

## Notas de configuración

**Dirección de los brokers según dónde corre el cliente:**

| Desde | Dirección |
|---|---|
| host | `localhost:9092,localhost:9093,localhost:9094` |
| contenedor en la red del compose | `kafka1:29092,kafka2:29092,kafka3:29092` |
| fuera de AWS hacia una EC2 | `<ip-pública>:9092` (con `KAFKA_HOST` en `.env`) |

**`bootstrap.servers` va sin esquema.** Con `http://` Kafka falla con
`Invalid url in bootstrap.servers`.

**Healthcheck de Zookeeper con `srvr`, no con `ruok`**: desde Zookeeper 3.5 `ruok` está fuera de
la lista permitida.

**Las credenciales no van en el código.** Se leen de variables de entorno (`.env`, ignorado por
git); `application.yaml` sólo trae defaults de desarrollo.

**CORS con orígenes concretos.** `CORS_ORIGENES` (por defecto 5173, 5174, 8090, 8091) alimenta
dos lugares: el `CorsConfig` de MVC para `/api/**` y `management.endpoints.web.cors` para
`/actuator/**`. Actuator **no** pasa por el `CorsConfig`; sin la segunda declaración el
navegador bloquea el sondeo de salud y los frontends muestran los servicios caídos estando
arriba.

**Actuator expone sólo `health`**: `env`, `beans` y `heapdump` filtran configuración sin
autenticación. Spring Boot no trae indicador de salud para Kafka; `ms-kafka-admin` agrega
`KafkaClusterHealthIndicator`, que reporta DOWN con menos de 2 brokers (no se puede honrar
`min.insync.replicas=2`).
