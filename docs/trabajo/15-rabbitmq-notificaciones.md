# 15 · RabbitMQ, la integración que faltó en la EV2: clúster de 3 nodos, DLQ con registro y administración

Commits `34c7d51` (clúster `infra/rabbitmq/` + ms-notificaciones, puerto 8088) y `be64757`
(ms-rabbit-admin, puerto 8089; el `crear-topicos.sh` del mismo commit es Kafka y no se trata
aquí). El productor es ms-carrito, commit `19e2dfe` (doc 14). Los tres nodos entran al compose
raíz en `6116e80` (doc 17). Contrato: `PLAN-INTEGRACION-EV3.md` §9, §10, §11 y §13.

## El problema

En la evaluación 2 la integración con RabbitMQ **no se entregó**: el repositorio no tenía
broker, ni productor, ni consumidor, ni cola de mensajes muertos (doc 09, tabla de
incumplimientos). La EV3 la arrastra como pendiente con cinco exigencias concretas
(`PLAN-INTEGRACION-EV3.md` §0, fila "EV2 pendiente"): **productores y consumidores, DLQ con
logs, administración de colas/exchanges/bindings y un clúster en el compose**. Este documento
deja, para cada una, dónde vive en el código y cómo se muestra funcionando.

### Las cinco exigencias, una por una

| Exigencia | Cómo se cumple | Dónde (clase / archivo) | Cómo se demuestra (guion abajo) |
|---|---|---|---|
| **Productores** | ms-carrito publica 5 tipos de notificación (`carrito.producto-agregado`, `pedido.creado`, `pedido.pagado`, `pedido.rechazado`, `envio.estado-actualizado`) en el exchange topic `pedidos360.notificaciones`, con publisher confirms (`correlated`), returns + `mandatory`, mensajes `PERSISTENT` y `messageId` | `ms-carrito/.../Component/PublicadorNotificaciones`, `CallbacksConfirmacionRabbit`, `Config/RabbitConfig`; `DTO/TipoNotificacion` | paso 3: log "RabbitMQ confirmó (ack) la notificación correlationId=…" |
| **Consumidores** | ms-notificaciones tiene **tres** `@RabbitListener`: `notificaciones.cliente` (persiste y empuja por SSE), `notificaciones.auditoria` (binding `#`, log de todo) y la DLQ | `ms-notificaciones/.../Component/ConsumidorNotificaciones`, `ConsumidorAuditoria`, `ConsumidorDlq`; lógica en `Service/ServicioNotificaciones`, `ServicioAuditoria`, `ServicioMensajesFallidos` | pasos 2 y 3: `list_queues` con 1 consumidor por cola; log "Mensaje recibido: cola=notificaciones.cliente…" y `[AUDITORIA]` |
| **DLQ con logs** | `notificaciones.cliente` tiene `x-dead-letter-exchange = pedidos360.notificaciones.dlx` (fanout) → `notificaciones.cliente.dlq`. Payload inválido: rechazo **inmediato** sin reencolar; error transitorio: 3 intentos (1 s, ×2) y después rechazo. El consumidor de la DLQ loguea ERROR con los headers `x-death` (cola, motivo, conteo, exchange y routing key de origen) y lo guarda en `mensaje_fallido` | `ms-notificaciones/.../Config/RabbitConfig` (política de reintentos), `Config/TopologiaRabbitConfig`, `Exception/PayloadInvalidoException`, `Component/ConsumidorDlq`, `Model/MensajeFallido`; `GET /api/notificaciones/fallidas` | paso 4: log "MENSAJE MUERTO en DLQ: colaOrigen=notificaciones.cliente motivo=rejected…" y la fila en `/fallidas` |
| **Admin de colas, exchanges y bindings** | ms-rabbit-admin crea, lista y borra los tres; purga colas; muestra el estado del clúster. Escribe por AMQP (`AmqpAdmin`) y lee por la API de management con failover entre los 3 nodos. Protege `""`, `amq.*` y la topología del sistema | `ms-rabbit-admin/.../Controller/RabbitAdminController`, `Service/ServicioRabbitAdmin`, `Component/ClienteManagementRabbit`, `Component/ProteccionRecursos` | paso 6: `curl` a `:8089/api/rabbit/*`, y el 403 al intentar borrar `notificaciones.cliente` |
| **Clúster en el compose** | 3 nodos `rabbitmq:3.13-management-alpine` con descubrimiento `classic_config`, cookie Erlang compartida, `pause_minority`, colas **quorum** con reconciliación a 3 réplicas. Standalone en `infra/rabbitmq/` y dentro del compose raíz | `infra/rabbitmq/docker-compose.yml`, `infra/rabbitmq/rabbitmq.conf`, `docker-compose.yml` (servicios `rabbitmq1..3`) | paso 1: `rabbitmqctl cluster_status` con 3 nodos y sin particiones; UI en http://localhost:15672 |

## Qué se hizo

```
ms-carrito                                   clúster "pedidos360": rabbit@rabbitmq1, @rabbitmq2, @rabbitmq3
PublicadorNotificaciones ──confirm/returns──▶ pedidos360.notificaciones (topic, durable)
                                               ├─ carrito.* pedido.* envio.* ─▶ notificaciones.cliente (quorum)
                                               │     x-dead-letter-exchange, x-dead-letter-strategy=at-least-once,
                                               │     x-overflow=reject-publish, x-delivery-limit=5
                                               │        └─▶ ConsumidorNotificaciones ─ valida ─▶ ServicioNotificaciones
                                               │              │                                   ├ tabla notificacion (PK = idNotificacion)
                                               │              │                                   └ EmisorSse ─▶ GET /api/notificaciones/stream
                                               │              ├ payload inválido ──── rechazo sin reencolar ──┐
                                               │              └ error transitorio ─ 3 intentos ─ rechazo ─────┤
                                               │                                                              ▼
                                               │                                pedidos360.notificaciones.dlx (fanout)
                                               │                                     └─▶ notificaciones.cliente.dlq (quorum)
                                               │                                           └─▶ ConsumidorDlq: log ERROR x-death + mensaje_fallido
                                               └─ # ─▶ notificaciones.auditoria (quorum) ─▶ ConsumidorAuditoria: log [AUDITORIA]

ms-rabbit-admin ── AmqpAdmin (declarar, borrar, purgar) ──────────▶ AMQP 5672/5673/5674
               └── RestClient + basic auth (listar, nodos) ───────▶ management 15672 → 15673 → 15674 (failover)
```

Acceso: AMQP en `localhost:5672/5673/5674`, management en **http://localhost:15672** (también
15673 y 15674, el mismo clúster), usuario **`pedidos360`** / clave **`pedidos360`**
(`RABBITMQ_USER`/`RABBITMQ_PASS`). Dentro de Docker los servicios usan
`RABBITMQ_ADDRESSES=rabbitmq1:5672,rabbitmq2:5672,rabbitmq3:5672` (ancla `spring-env` del
compose raíz).

### Guion de demostración en vivo

Desde la raíz del repo con el sistema arriba (`docker compose up -d`, 22 contenedores sanos).

**1 · Clúster** — 3 nodos, sin particiones, réplicas Raft de cada cola:

```bash
docker exec rabbitmq1 rabbitmqctl cluster_status
docker exec rabbitmq1 rabbitmq-queues quorum_status notificaciones.cliente
curl -s localhost:8089/api/rabbit/cluster          # nombreCluster, nodosCorriendo, hayQuorum, hayParticiones
```

En la UI (http://localhost:15672, `pedidos360`/`pedidos360`): *Overview → Nodes* muestra los
tres; *Queues* muestra las tres colas `quorum` con su líder y "+2" réplicas.

**2 · Topología** — colas, exchanges y los 5 bindings:

```bash
docker exec rabbitmq1 rabbitmqctl list_queues name type messages consumers
docker exec rabbitmq1 rabbitmqctl list_exchanges name type durable | grep pedidos360
docker exec rabbitmq1 rabbitmqctl list_bindings source_name destination_name routing_key | grep pedidos360
```

**3 · Productor → consumidor → navegador** (tres terminales):

```bash
# A: el "navegador" (EventSource no manda headers: el usuario va por query)
curl -N 'http://localhost:8088/api/notificaciones/stream?usuarioId=demo@correo.cl'
# B: los logs que prueban cada salto
docker compose logs -f ms-carrito ms-notificaciones | grep -E 'RabbitMQ|Mensaje recibido|Notificación guardada|AUDITORIA'
# C: el evento de negocio
curl -s -X POST localhost:8087/api/carrito/items -H 'X-Usuario-Id: demo@correo.cl' \
     -H 'Content-Type: application/json' -d '{"productoId":"item_107","cantidad":1}'
curl -s 'localhost:8088/api/notificaciones?usuarioId=demo@correo.cl'
```

Se espera, en orden: "Notificación enviada a RabbitMQ (esperando confirmación):
exchange=pedidos360.notificaciones routingKey=carrito.producto-agregado correlationId=…",
"RabbitMQ confirmó (ack)…", "Mensaje recibido: cola=notificaciones.cliente…", la línea
`[AUDITORIA] routingKey=carrito.producto-agregado` (el **mismo** mensaje en dos colas por
patrón) y "Notificación guardada: … conexionesSse=1"; en la terminal A aparece
`event:notificacion`.

**4 · DLQ** — un mensaje sin `usuarioId`, publicado a mano por la API de management:

```bash
curl -s -u pedidos360:pedidos360 -H 'Content-Type: application/json' \
  -X POST 'http://localhost:15672/api/exchanges/%2F/pedidos360.notificaciones/publish' \
  -d '{"routing_key":"pedido.creado","properties":{"content_type":"application/json","message_id":"demo-dlq-1","delivery_mode":2},"payload":"{\"idNotificacion\":\"demo-dlq-1\",\"tipo\":\"pedido.creado\",\"titulo\":\"Demo\",\"mensaje\":\"sin usuarioId\"}","payload_encoding":"string"}'
docker compose logs ms-notificaciones | grep -E 'SIN reencolar|MENSAJE MUERTO'
curl -s localhost:8088/api/notificaciones/fallidas
```

Se espera `{"routed":true}`, una sola línea "Fallo procesando mensaje RabbitMQ (intento 1/3)"
(no se reintenta), "Mensaje rechazado SIN reencolar -> DLX -> DLQ: … messageId=demo-dlq-1
motivo=PayloadInvalidoException", "MENSAJE MUERTO en DLQ: colaOrigen=notificaciones.cliente
motivo=rejected conteo=1 exchangeOrigen=pedidos360.notificaciones routingKeys=[pedido.creado]"
y la fila en `/fallidas`. Variante "JSON roto": `"payload":"esto no es json"` falla al
convertir (`MessageConversionException`, no reintentable) y sigue el mismo camino; el motivo del
log es la causa raíz de Jackson. La DLQ queda en 0 mensajes porque `ConsumidorDlq` la
vacía al instante; la evidencia es el log, la tabla y la tasa *deliver* de
`notificaciones.cliente.dlq` en la UI.

**5 · Resiliencia del consumidor** (verificado el 22-nov):

```bash
docker stop ms-notificaciones
python infra/prueba-humo.py        # la compra queda PAGADA; falla sólo el paso 6 (notificaciones)
docker exec rabbitmq1 rabbitmqctl list_queues name messages consumers   # notificaciones.cliente  3  0
docker start ms-notificaciones     # espera a que vuelva healthy (docker compose ps)
docker exec rabbitmq1 rabbitmqctl list_queues name messages consumers   # notificaciones.cliente  0  1
```

**6 · Administración** por ms-rabbit-admin:

```bash
curl -s -X POST localhost:8089/api/rabbit/exchanges -H 'Content-Type: application/json' -d '{"nombre":"demo.eventos","tipo":"topic"}'
curl -s -X POST localhost:8089/api/rabbit/colas     -H 'Content-Type: application/json' -d '{"nombre":"demo.cola"}'     # quorum y durable por defecto
curl -s -X POST localhost:8089/api/rabbit/bindings  -H 'Content-Type: application/json' -d '{"origen":"demo.eventos","destino":"demo.cola","routingKey":"demo.#"}'
curl -s localhost:8089/api/rabbit/colas            # tipo, mensajes, consumidores, nodo líder, miembros, protegida
curl -s localhost:8089/api/rabbit/exchanges
curl -s localhost:8089/api/rabbit/bindings         # ?incluirPorDefecto=true muestra los implícitos
curl -s -X DELETE localhost:8089/api/rabbit/bindings -H 'Content-Type: application/json' -d '{"origen":"demo.eventos","destino":"demo.cola","routingKey":"demo.#"}'
curl -s -X DELETE 'localhost:8089/api/rabbit/colas/demo.cola?siVacia=true'
curl -s -X DELETE localhost:8089/api/rabbit/exchanges/demo.eventos
curl -i -X DELETE localhost:8089/api/rabbit/colas/notificaciones.cliente   # 403: topología del sistema, pide ?forzar=true
curl -i -X DELETE localhost:8089/api/rabbit/exchanges/amq.topic            # 403: prefijo reservado por el broker
curl -i -X POST localhost:8089/api/rabbit/exchanges -H 'Content-Type: application/json' -d '{"nombre":"x","tipo":"fifo"}'   # 400
```

**7 · Caída de un nodo** (no verificado; ver abajo):

```bash
docker stop rabbitmq2
curl -s localhost:8089/api/rabbit/cluster          # totalNodos 3, nodosCorriendo 2, hayQuorum true
docker exec rabbitmq1 rabbitmq-queues quorum_status notificaciones.cliente
python infra/prueba-humo.py                        # las notificaciones deberían seguir llegando
docker start rabbitmq2
```

## Decisiones y su porqué

### Clúster (`infra/rabbitmq/`)

| Decisión | Porqué | Exigencia |
|---|---|---|
| **3** nodos, no 1 ni 2 | una cola quorum (Raft) necesita mayoría para aceptar escrituras: con 3 la mayoría es 2 y se tolera la caída de uno; con 2 la mayoría también es 2 y una caída deja la cola sin líder | EV2 · clúster |
| `cluster_formation.peer_discovery_backend = classic_config` con los 3 `rabbit@rabbitmqN` | lista fija y conocida: no depende de DNS SRV, Consul ni Kubernetes. Es lo determinista en un compose | EV2 · clúster |
| `rabbitmq2` y `rabbitmq3` con `depends_on: rabbitmq1 (service_healthy)`; healthcheck `rabbitmq-diagnostics -q ping` | sin esperar, dos nodos vacíos pueden no encontrarse y formar cada uno su propio clúster de 1 | EV2 · clúster |
| `hostname` fijo por nodo y un volumen nombrado por nodo | el nombre del nodo es `rabbit@<hostname>` y es la clave de sus datos; con el id aleatorio del contenedor el nodo no reconoce su volumen. Compartir un volumen corrompe el log Raft | EV2 · clúster |
| `RABBITMQ_ERLANG_COOKIE` idéntica en los tres (ancla YAML) | es el secreto con que los nodos se autentican entre sí; distinta, no se unen. La 3.13 la marca deprecada, pero es la forma más simple de compartir un valor | EV2 · clúster |
| `cluster_partition_handling = pause_minority` | con 3 nodos siempre hay un lado mayoritario; el minoritario se pausa en vez de aceptar escrituras que después habría que reconciliar a mano | EV2 · clúster |
| `quorum_queue.continuous_membership_reconciliation` con `target_group_size = 3` | una quorum toma réplicas de los nodos presentes **al declararla**: si un servicio la declara cuando sólo está `rabbitmq1`, quedaría con 1 réplica para siempre. La reconciliación la hace crecer a 3 sola | EV2 · clúster |
| `vm_memory_high_watermark.absolute = 384MB` con `mem_limit: 512m`; `disk_free_limit.absolute = 1GB` | el umbral relativo se calcula sobre la RAM que ve el proceso (la del host): el nodo creería tener gigas y el kernel lo mataría antes de frenar a los publicadores | EV2 · clúster |
| Los clientes usan la lista de 3 direcciones con `address-shuffle-mode: random` | si cae el nodo al que estaba conectado, el cliente reconecta al siguiente sin reiniciar; `random` reparte las conexiones entre nodos | EV2 · clúster |

### Topología y consumidor (ms-notificaciones)

| Decisión | Porqué | Exigencia |
|---|---|---|
| Exchange **topic** `pedidos360.notificaciones`; la cola cliente se suscribe por patrón (`carrito.*`, `pedido.*`, `envio.*`) y la auditoría con `#` | cada cola elige qué recibe sin que el productor sepa cuántas hay; la auditoría demuestra que un mismo mensaje llega a dos colas | EV2 · consumidores, §9 |
| Las **tres** colas son quorum, durables | un mensaje confirmado al productor ya está en la mayoría y sobrevive a la caída de un nodo | EV2 · clúster, §9 |
| DLX **fanout** `pedidos360.notificaciones.dlx` → `notificaciones.cliente.dlq` | a la DLQ va todo lo muerto sin importar su routing key; la original queda en `x-death` | EV2 · DLQ |
| `x-dead-letter-strategy = at-least-once` **con** `x-overflow = reject-publish` | con el default (at-most-once) un mensaje muerto puede perderse camino a la DLQ. RabbitMQ exige `reject-publish` para at-least-once (con `drop-head` vuelve en silencio a at-most-once); y con la cola llena el productor recibe nack y lo loguea | EV2 · DLQ |
| `x-delivery-limit = 5` **además** de los 3 intentos de la aplicación | son capas distintas. Los 3 intentos ocurren dentro del consumidor (stateless, el broker ve **una** entrega). El límite del broker atrapa al mensaje "veneno" que tumba al consumidor antes de que alcance a rechazarlo y se reentregaría para siempre; en `x-death` aparece como `delivery_limit` | EV2 · DLQ |
| Política de reintentos propia en `RabbitConfig.crearPlantillaReintentos`, **no** `spring.rabbitmq.listener.simple.retry.enabled` como dice §9 | la de Boot reintenta todo, incluido un JSON roto. Aquí `AmqpRejectAndDontRequeueException`, `MessageConversionException` y `MethodArgumentNotValidException` no se reintentan (`traverseCauses = true`); lo demás, 3 intentos totales con backoff 1 s ×2 (tope 10 s) y luego `RejectAndDontRequeueRecoverer` | EV2 · DLQ, §9 |
| `PayloadInvalidoException extends AmqpRejectAndDontRequeueException`; `ConsumidorNotificaciones.validar` la lanza sin cuerpo, con violaciones de `Validator` o con un `tipo` fuera de `TipoNotificacion` | es la señal que Spring AMQP entiende como "rechazar sin reencolar": un mensaje sin `usuarioId` no tiene a quién entregarse, y reintentarlo no lo cambia | EV2 · DLQ, §10 |
| `tipo` ≠ routing key sólo loguea WARN | el contenido es válido; indica un productor que publica mal, no un mensaje irrecuperable | §10 |
| `acknowledge-mode: auto`, `default-requeue-rejected: false`, `prefetch: 10`, `concurrency 1..3`, `missing-queues-fatal: false` | ack sólo si el listener termina bien; lo rechazado no vuelve a la cola (bucle); poco en vuelo si el servicio cae; el servicio arranca aunque el clúster aún no tenga las colas | EV2 · consumidores |
| Tres container factories: la por defecto (JSON → `NotificacionDTO`), `fabricaMensajesCrudos` (auditoría) y `fabricaDlq` | la auditoría recibe el `Message` crudo con `SimpleMessageConverter` porque debe registrar también lo mal formado. La DLQ es el final del camino y **no** tiene DLX: si falla guardar el mensaje muerto, `fabricaDlq` lo **reencola** (`ImmediateRequeueMessageRecoverer`) en vez de borrar la evidencia | EV2 · DLQ |
| `ConsumidorDlq.extraer` lee `x-death` a la defensiva: la entrada más reciente da cola, motivo (`rejected`, `delivery_limit`, `expired`, `maxlen`), conteo, exchange y routing keys; sin `x-death` registra `sin-x-death` | un mensaje publicado a mano directo en la DLQ no lo trae, y lanzar ahí lo reencolaría para siempre | EV2 · DLQ con logs |
| Además del log ERROR, la tabla `mensaje_fallido` y `GET /api/notificaciones/fallidas` (100 más recientes) | el log se pierde al rotar; la tabla es la evidencia consultable desde la consola | EV2 · DLQ con logs |
| Idempotencia por `idNotificacion` (PK de `notificacion`): `existsById` y, si dos hilos chocan, `DataIntegrityViolationException` = duplicado ignorado | at-least-once implica reentregas; el `messageId` lo pone el productor | §10 |
| `ServicioNotificaciones.procesar` **sin** `@Transactional`: el `save` confirma antes de emitir por SSE | el navegador nunca ve una notificación que después no aparece en el listado por un rollback; un error de base se propaga y entra al reintento | §10 |
| SSE (`EmisorSse`) y no WebSocket; usuario por `?usuarioId=`; evento `conectado` al abrir; heartbeat cada 20 s; timeout 30 min | el flujo es sólo servidor → navegador y `EventSource` reconecta solo, pero no manda headers. El heartbeat mantiene viva la conexión a través de proxies; el timeout evita acumular emisores de pestañas cerradas. Una pestaña muerta no propaga el error (no manda el mensaje a la DLQ) | §10 |
| Topología como `Declarables` en **ambos** servicios (`TopologiaRabbitConfig.crearTopologia` aquí, `RabbitConfig.topologiaNotificaciones` en ms-carrito), con los mismos argumentos fijados por tests | ningún servicio depende del orden de arranque ni de un script; `RabbitAdmin` la vuelve a declarar en cada reconexión, así que una cola borrada a mano reaparece | §9 |

### Administración (ms-rabbit-admin)

| Decisión | Porqué | Exigencia |
|---|---|---|
| Escribe por AMQP (`AmqpAdmin`: `declareQueue/Exchange/Binding`, `deleteQueue/Exchange`, `removeBinding`, `purgeQueue`) y lee por la API HTTP de management | AMQP no sabe enumerar recursos ni ver nodos; la API de management no es la vía con que los servicios declaran su topología | EV2 · admin, §11 |
| `RABBITMQ_MGMT_URL` admite una **lista** (`http://rabbitmq1:15672,http://rabbitmq2:15672,http://rabbitmq3:15672` en el compose); `ClienteManagementRabbit` prueba la siguiente ante conexión rechazada, timeout o 5xx y recuerda la última que respondió; un 4xx **no** hace failover | §11 nombraba una sola URL: con el nodo 1 caído la consola quedaría ciega justo cuando más importa mirar el clúster. 401 es credencial y 404 es "no existe": otro nodo diría lo mismo | EV2 · admin + clúster |
| Protegidos siempre `""` y `amq.*` (fijo en código, no configurable); la topología de §9 (`app.rabbit.proteccion.sistema`) exige `?forzar=true`; sin él, **403** con el motivo | borrar `notificaciones.cliente` deja a ms-carrito publicando con `mandatory` y cada mensaje vuelve "sin ruta" hasta que un servicio reconecte y la redeclare. `amq.*` lo reserva el broker: ni forzando se borra (también 403) | §11 |
| Un binding es protegido si toca un recurso del sistema en cualquiera de sus puntas; uno sobre `amq.topic` hecho a mano no | quitar `pedidos360.notificaciones → notificaciones.cliente` corta las notificaciones aunque las dos puntas sigan existiendo | §11 |
| Crear cola o exchange existente → **409** (se consulta antes) | redeclarar idéntico es inocuo y no avisaría nada; con otros argumentos el broker cierra el canal con `PRECONDITION_FAILED` | §11 |
| Cola nueva: `quorum` y durable por defecto; quorum no durable → 400; `limiteEntregas` en una classic → 400; `x-queue-type=classic` explícito | es el estándar del clúster; el broker rechaza una quorum no durable e ignoraría en silencio `x-delivery-limit` en una classic; una política `default-queue-type` del vhost podría convertir una classic implícita | §11 |
| `siVacia=true`: chequeo propio (409 explicativo) **y** `if-empty` al broker | el broker decide de forma atómica si llegó un mensaje entre la consulta y el borrado | §11 |
| Validación en DTOs: nombres `^[A-Za-z0-9._:-]+$`, ≤ 255, sin prefijo `amq.`; exchange ∈ {direct, topic, fanout, headers}; cola ∈ {quorum, classic}; `routingKey` obligatoria pero puede ser `""` | lo que el broker rechazaría se corta antes con un 400 legible | §11 |
| Errores de canal AMQP traducidos: 404 → 404, 403 → 403, 405/406 → 409, broker inalcanzable → 503, management caída → 503, credenciales rechazadas → 502 (`Controller/ManejadorErrores`) | la consola recibe el texto del broker y un código con significado, no un 500 genérico | §3.5 |
| `GET /api/rabbit/cluster`: `hayQuorum` = más de la mitad de los nodos corriendo; `hayParticiones`, `hayAlarmas` (memoria, disco), `fuente` = URL que respondió | es la condición Raft (2 de 3 sí, 1 de 3 no); si `fuente` no es la primera URL, hubo failover | EV2 · clúster |
| Rutas armadas segmento a segmento (`ClienteManagementRabbit.construirUri`) | el vhost `/` debe ir como `%2F` y un nombre con caracteres especiales no puede inyectar segmentos de ruta | §11 |
| El servicio arranca sin broker (`RabbitAdmin` conecta en la primera operación) | lo cubre `MsRabbitAdminApplicationTests`; la consola ve un 503 en vez de un servicio caído | — |

## Verificación

| Qué | Resultado |
|---|---|
| `mvn -B test` en `ms-notificaciones` | BUILD SUCCESS, **31 tests**, sin broker (RabbitMQ no tiene broker embebido; §3.6) |
| `TopologiaRabbitConfigTest` (ms-notificaciones) y `RabbitConfigTest` (ms-carrito) | cola cliente quorum con DLX, `at-least-once`, `reject-publish` y límite 5; auditoría y DLQ quorum sin DLX; exchanges topic y fanout durables; los 5 bindings. Los dos lados fijan los **mismos** argumentos |
| `RabbitConfigReintentosTest` | payload inválido y JSON roto **no** se reintentan; error transitorio se reintenta 3 veces; el recuperador rechaza sin reencolar |
| `ConsumidorNotificacionesTest`, `ConsumidorDlqTest` | sin `usuarioId`, tipo desconocido o cuerpo nulo → rechazo sin reencolar; error del servicio se propaga para reintentar; la DLQ loguea ERROR con los campos de `x-death` (capturado con `OutputCaptureExtension`), soporta mensajes sin `x-death` y el motivo `delivery_limit` |
| `MsNotificacionesIntegracionTest`, `ServicioNotificacionesIntegracionTest` (H2) | los tres listeners registrados sin arrancar y la topología como beans; el stream SSE recibe la notificación procesada; el mensaje muerto queda en `mensaje_fallido` y se ve en `/fallidas`; el JSON del productor se convierte al DTO local; duplicado ignorado sin emitir dos veces |
| `mvn -B test` en `ms-rabbit-admin` | BUILD SUCCESS, **93 tests** (`@WebMvcTest`, `AmqpAdmin` mockeado, `MockRestServiceServer` con JSON reales de la API en `src/test/resources/management/`) |
| `ClienteManagementRabbitTest` | nodo caído y 5xx hacen failover y el siguiente queda preferido; todos caídos → error con cada URL; 401 no hace failover; nodo caído sin métricas se parsea |
| `ServicioRabbitAdminTest`, `ProteccionWebTest`, `RabbitAdminControllerTest` | protección de `amq.*`, del exchange por defecto y del sistema (403 sin forzar, borrado con forzar); `amq.*` forzado que el broker rechaza sigue en 403; 409 por existente o `siVacia` con mensajes; `PRECONDITION_FAILED` → 409; broker caído → 503; clúster con 1 nodo caído **mantiene** quorum y con 2 lo pierde |
| Mutante ms-notificaciones, en una copia fuera del repo (22-nov): se borró `reintentables.put(AmqpRejectAndDontRequeueException.class, false)` de `RabbitConfig.crearPlantillaReintentos`, `mvn -B -o test` | BUILD FAILURE, **31 tests, 1 falla**: `RabbitConfigReintentosTest.payloadInvalidoNoSeReintenta` |
| Mutante ms-rabbit-admin, misma copia: se anuló la rama `amq.` de `ProteccionRecursos.esProtegido` | BUILD FAILURE, **93 tests, 13 fallas**: 8 en `ProteccionRecursosTest`, 2 en `ProteccionWebTest`, 3 en `ServicioRabbitAdminTest` |
| `rabbitmqctl cluster_status` en el sistema real (22-nov, `docker compose up`, 22 contenedores sanos) | **3 nodos corriendo** (`rabbit@rabbitmq1..3`), particiones de red `{}` |
| `list_queues` / `list_bindings` en el mismo sistema (salidas en `evidencias/ev3-docker/README.md`) | `notificaciones.cliente`, `notificaciones.auditoria`, `notificaciones.cliente.dlq`: tipo **quorum**, 0 mensajes, **1 consumidor** cada una; bindings `pedidos360.notificaciones → notificaciones.cliente` (`carrito.*`, `pedido.*`, `envio.*`), `→ notificaciones.auditoria` (`#`), `pedidos360.notificaciones.dlx → notificaciones.cliente.dlq` |
| Prueba de humo `infra/prueba-humo.py`, 25/25 dos veces seguidas | `carrito.producto-agregado`, `pedido.creado` y `pedido.pagado` **entregadas por RabbitMQ** a ms-notificaciones y listadas por `GET /api/notificaciones`; `GET :8089/api/rabbit/cluster` → `nombreCluster "pedidos360"`, `totalNodos 3`, `nodosCorriendo 3`, `hayQuorum true`, `hayParticiones false` |
| Resiliencia con `docker stop ms-notificaciones` | la compra igual quedó **PAGADA**; `list_queues` mostró `notificaciones.cliente` con **3 mensajes y 0 consumidores**; al reiniciar el servicio la cola bajó a **0** con 1 consumidor y las notificaciones aparecieron en el listado |
| Tienda en el navegador | la campana de notificaciones recibió los avisos del flujo de compra |

### No verificado

- **Un mensaje real llegando a la DLQ en el broker.** El camino rechazo → DLX → DLQ →
  `ConsumidorDlq` → `mensaje_fallido` está probado por partes con mocks y H2; contra RabbitMQ
  real nunca se publicó un mensaje inválido. El paso 4 del guion es exactamente esa prueba
  pendiente: **córrela antes de la presentación**.
- **Caída de un nodo RabbitMQ** con las colas quorum (paso 7): no se detuvo ningún nodo. La
  tolerancia está razonada (Raft, mayoría 2 de 3) y el cálculo de `hayQuorum` está probado con
  JSON de nodos caídos, pero no se observó un cambio de líder ni la reconexión de los clientes.
- **El failover de la API de management** contra nodos reales: sólo con `MockRestServiceServer`.
- **El CRUD de ms-rabbit-admin contra el broker real** (paso 6): la prueba de humo sólo llama a
  `GET /api/rabbit/cluster`; crear, listar y borrar colas/exchanges/bindings está cubierto con
  `AmqpAdmin` mockeado.
- **Los comandos del guion tal como están escritos**: al redactar esto Docker estaba apagado.
  Lo ejecutado el 22-nov son `cluster_status`, `list_queues`, `list_bindings`, la prueba de humo
  y la resiliencia del paso 5.
- **SSE en el navegador** más allá de la campana de la tienda: reconexión de `EventSource`,
  varias pestañas del mismo usuario, heartbeat a través de un proxy.
- **El camino de 3 reintentos → DLQ** por un error transitorio (base caída) contra el broker.
- **Seguridad**: Cognito no está implementado. ms-rabbit-admin y `GET /api/notificaciones/fallidas`
  quedan abiertos a cualquiera que alcance el puerto.

## Pendiente

- **Pantalla de RabbitMQ en la consola** sobre `:8089/api/rabbit/*` y `:8088/api/notificaciones/fallidas`:
  hoy la administración se demuestra con `curl` o la UI de management (`PENDIENTES.md` P1f).
- **Ejecutar los pasos 4 y 7 del guion** con el sistema arriba y guardar las salidas en
  `evidencias/` (`PENDIENTES.md` P7). Son los dos huecos de la tabla anterior que un evaluador
  puede pedir ver.
- **Rol ADMIN** en ms-rabbit-admin y `/fallidas` (§12, `PENDIENTES.md` P4).
- **`PATCH /api/notificaciones/{id}/leida` no verifica dueño** (`NotificacionesController.marcarLeida`):
  cualquiera que conozca el id la marca. Con Cognito, comparar con el usuario del token.
- **Reproceso de la DLQ**: hoy sólo se registra. Si se quiere reinyectar, un endpoint admin que lea
  `mensaje_fallido` y republique al exchange original.

## Trampas para quien siga

- `infra/rabbitmq/docker-compose.yml` y el compose raíz usan los **mismos** `container_name`
  (`rabbitmq1..3`) y puertos: no se pueden levantar a la vez. Además tienen volúmenes distintos
  (`rabbitmq1-datos` contra `rabbitmq1_data`): son dos clústeres con estado propio.
- Los argumentos de `notificaciones.cliente` deben ser **idénticos** en ms-carrito (escritos a
  mano en `RabbitConfig`) y ms-notificaciones (`app.rabbit.topologia.limite-entregas`). Si
  difieren, el segundo en arrancar recibe `PRECONDITION_FAILED` ("inequivalent arg"). Una cola
  ya creada no cambia de argumentos al redeclararla: hay que borrarla (`?forzar=true`) o
  `docker compose down -v`.
- `POST /api/rabbit/colas/{nombre}/purgar` **no** está protegido: purgar `notificaciones.cliente`
  descarta notificaciones pendientes, y purgar la DLQ borra mensajes que aún no se registraron.
- `DELETE /api/rabbit/bindings` no consulta si el binding existía (`ServicioRabbitAdmin.borrarBinding`
  va directo a `removeBinding`): con las dos puntas existentes, un 200 no prueba que hubiera algo
  que borrar. Para confirmarlo, `GET /api/rabbit/bindings` antes y después.
- Si MySQL cae, `ConsumidorDlq` reintenta 3 veces y **reencola de inmediato** (`fabricaDlq`): con
  la base abajo la DLQ gira en bucle y llena el log hasta que vuelva. Es el precio de no perder
  la evidencia; no lo "arregles" rechazando sin reencolar, porque la DLQ no tiene DLX.
- `notificaciones.auditoria` no tiene DLX: si `ConsumidorAuditoria` falla 3 veces, el mensaje se
  descarta, aunque el log de `RecuperadorHaciaDlq` diga "-> DLX -> DLQ" (la clase es la misma para
  las dos colas). Es aceptable porque sólo loguea; no le cuelgues lógica de negocio.
- Los reintentos son **stateless** (en memoria del consumidor): un mensaje que falla 3 veces llega
  a la DLQ con `conteo=1` y `motivo=rejected`, no 3. `delivery_limit` sólo aparece si el
  consumidor muere sin rechazar.
- Cada instancia de ms-notificaciones empuja SSE sólo a sus propias conexiones (`EmisorSse` en
  memoria). Con dos réplicas, un usuario conectado a la otra no recibe el push (sí lo verá en el
  listado).
- Para el guion, no detengas `rabbitmq1` si después vas a usar `docker exec rabbitmq1 …`: usa
  `rabbitmq2` para la caída o ejecuta los comandos en otro nodo.
- `UsuarioActual` de ms-notificaciones acepta `?usuarioId=` **o** el header, y dice que la fase 4
  leerá `sub`; el contrato §12 fija `email` y el token por `?access_token=` sólo en `/stream`.
