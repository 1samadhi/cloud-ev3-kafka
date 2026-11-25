# PLAN DE INTEGRACIÓN — Evaluación 3 (DSY1107) · contrato de construcción

> Este documento es el **contrato**. Cada sección se puede construir sin preguntar nada: si algo
> no está acá, el plan está incompleto — avisar, no improvisar. Reemplaza en alcance a
> `PLAN-CONSTRUCCION.md` (que se conserva como historia) y se apoya en
> `ARQUITECTURA-PEDIDOS360.md`.
>
> Entrega: **viernes 27-nov**. Fuentes de los requisitos: pauta EP5 DUOC (100 % = código Kafka
> del backend) + enunciado del docente (ms-pago, analítica, extras) + RabbitMQ pendiente de la
> EV2 (notificaciones). El docente dio libertad de stack: el frontend sigue en React + TS.

---

## 0. Qué se evalúa y dónde se gana cada punto

| Fuente | Requisito | Dónde se resuelve |
|---|---|---|
| Pauta 1 (12) | Config completa del cliente Kafka | §3.1 en **todos** los servicios |
| Pauta 2 (12) | Productores y consumidores en los tópicos, con DTO | §2, §4–§7 |
| Pauta 3 (6) · 9 (6) | Admin: crear, listar, **modificar** tópicos/configs; inspeccionar y operar **consumer groups** | §6 ms-kafka-admin + `infra/kafka/crear-topicos.sh` |
| Pauta 4 (14) | Productores con DTO y serializers adecuados | §3.2 |
| Pauta 5 (14) | `@KafkaListener` con la lógica **separada** de la mensajería | §3.3 |
| Pauta 6 (12) | Errores: validación, logging contextual, reintentos, DLT | §3.4 |
| Pauta 7 (12) | Retención, cleanup, particiones y réplicas **ajustadas a cada flujo** | §2.1 |
| Pauta 8 (12) | Consumer groups que **reparten** (concurrency) y **comparten** (fan-out) | §2.2 |
| Enunciado | Antifraude: **misma tarjeta, > 3 veces en 10 s** → bloqueo + alerta | §4 ms-pago |
| Enunciado | **Productos y Carrito** publican eventos (ver detalle, cupón, envío) | §7 ms-productos, §8 ms-carrito |
| Enunciado | Recomendaciones de productos **parecidos** a lo consultado | §5 ms-recomendaciones |
| +5 | Webpay real | ya hecho; §4 lo conecta a pedidos |
| +2 | Historial/recomendaciones en **DynamoDB** | §5 |
| +3 | Dashboard con **WebSocket**, ≥ 3 métricas en vivo | §5 + consola |
| EV2 pendiente | RabbitMQ: productores, consumidores, **DLQ + logs**, admin de colas/exchanges/bindings, **clúster** en compose | §9, §10, §11 |
| Encargo | Login IDaaS (**Cognito**) + filtro JWT en el backend | §12 (fase 4) |
| Encargo | Cada servicio compila y tiene pruebas básicas; `.gitignore` correcto | §3.6 |

---

## 1. Mapa de servicios

| Servicio | Puerto | Estado | Kafka | RabbitMQ | Persistencia |
|---|---|---|---|---|---|
| ms-pago | 8083 | existe, se endurece | produce `payment-attempts`, `pagos-resultado`; consume `payment-attempts` | — | MySQL `pagos_db` |
| ms-recomendaciones | 8084 | existe, se endurece | consume `interacciones-user` (2 grupos), `catalogo-productos`; produce `interacciones-user` (simulador) | — | **DynamoDB** |
| ms-kafka-admin | 8085 | existe, se amplía | AdminClient | — | — |
| ms-productos | 8086 | **nuevo** | produce `interacciones-user` (VER_PRODUCTO), `catalogo-productos` | — | MySQL `productos_db` |
| ms-carrito | 8087 | **nuevo** | produce `interacciones-user`; consume `pagos-resultado` | produce notificaciones | MySQL `carrito_db` |
| ms-notificaciones | 8088 | **nuevo** | — | consume `notificaciones.cliente`, `notificaciones.auditoria`, DLQ | MySQL `notificaciones_db` |
| ms-rabbit-admin | 8089 | **nuevo** | — | AmqpAdmin + API de management | — |
| consola (React) | 8090 (docker) · 5173 (dev) | existe | — | — | — |
| tienda (React) | 8091 (docker) · 5174 (dev) | se porta | — | — | — |
| kafka-ui | 8080 | existe | | | |
| RabbitMQ 1/2/3 | AMQP 5672/5673/5674 · mgmt 15672/15673/15674 | **nuevo** | | | |
| DynamoDB Local | 8000 | **nuevo** (dev; en AWS se usa el real) | | | |

**Regla de oro de la arquitectura** (es lo que se defiende en la presentación):
REST para comandos transaccionales (reservar stock, crear pedido) · **RabbitMQ** para
notificaciones punto a punto con entrega garantizada por mensaje y DLQ · **Kafka** para el log
de eventos analíticos con replay y fan-out. Son dominios de falla independientes: si Kafka cae,
las notificaciones siguen saliendo, y al revés. Un fallo al publicar en Kafka **nunca** rompe la
operación de negocio (se loguea y sigue).

---

## 2. Kafka: tópicos y grupos

### 2.1 Tópicos (criterio 7: configuración **distinta por flujo**, justificada)

Fuente única: `infra/kafka/crear-topicos.sh`, montado y ejecutado por el contenedor
`crear-topicos` del compose (idempotente con `--if-not-exists`).

| Tópico | Part. | RF | min ISR | Retención | Cleanup | Extra | Key | Por qué así |
|---|---|---|---|---|---|---|---|---|
| `interacciones-user` | **6** | 3 | 2 | 7 d (`604800000`) | delete | `compression.type=lz4` | `usuarioId` | El flujo de mayor volumen: más particiones permiten hasta 6 consumidores en paralelo; 7 d basta para reprocesar analítica; lz4 abarata el clickstream |
| `payment-attempts` | 3 | 3 | 2 | **30 d** (`2592000000`) | delete | | `huellaTarjeta` | Auditoría antifraude; la key por tarjeta garantiza orden por tarjeta, que la ventana de 10 s necesita |
| `pagos-resultado` | 3 | 3 | 2 | **90 d** (`7776000000`) | delete | | `idOrden` | Registro de ventas: se conserva más que el resto |
| `catalogo-productos` | 3 | 3 | 2 | infinita (`-1`) | **compact** | `min.compaction.lag.ms=60000`, `segment.ms=3600000`, `min.cleanable.dirty.ratio=0.1` | `productoId` | Estado, no historia: sólo importa el último snapshot de cada producto; un consumidor nuevo reconstruye el catálogo completo leyendo desde el inicio |
| `<tópico>.DLT` (×4) | igual al origen | 3 | 2 | 14 d (`1209600000`) | delete | | la original | `DeadLetterPublishingRecoverer` publica en la **misma partición** del original: el DLT necesita al menos las mismas particiones |

Todos con `segment.bytes=268435456` salvo `catalogo-productos` (usa `segment.ms`).

### 2.2 Consumer groups (criterio 8)

| Grupo | Servicio | Tópico | concurrency | offset inicial | Qué demuestra |
|---|---|---|---|---|---|
| `grupo-recomendaciones` | ms-recomendaciones | `interacciones-user` | 3 | earliest | **reparto**: 3 hilos se dividen 6 particiones |
| `grupo-dashboard` | ms-recomendaciones | `interacciones-user` | 1 | **latest** | **fan-out**: mismo tópico, offsets independientes; sólo interesa lo que pasa ahora |
| `grupo-catalogo-recomendaciones` | ms-recomendaciones | `catalogo-productos` | 1 | earliest | reconstrucción de estado desde un tópico compactado |
| `grupo-antifraude` | ms-pago | `payment-attempts` | 3 | earliest | reparto por tarjeta |
| `grupo-carrito` | ms-carrito | `pagos-resultado` | 3 | earliest | coreografía de pedidos |
| `grupo-dlt-<servicio>` | cada consumidor | `<tópico>.DLT` | 1 | earliest | registro de mensajes muertos |

Los grupos viejos (`payment-velocity-group`, `recommendation-consumer-group`) se reemplazan.

### 2.3 DTOs de evento (cada servicio tiene su propia copia; NO hay librería compartida)

```
EventoInteraccionDTO            → interacciones-user
  idEvento        String  UUID, obligatorio
  usuarioId       String  obligatorio (también es la key)
  tipoEvento      enum    VER_PRODUCTO | AGREGAR_CARRITO | APLICAR_CUPON | GESTIONAR_ENVIO | COMPRA
  productoId      String  obligatorio salvo APLICAR_CUPON y GESTIONAR_ENVIO
  categoria       String  slug (ver §7.1), opcional
  subcategoria    String  opcional
  marca           String  opcional
  origen          String  "ms-productos" | "ms-carrito" | "ms-recomendaciones"
  detalle         Map<String,String>  opcional (cupon, metodoEnvio, comuna, cantidad…)
  timestamp       long    epoch millis

IntentoPagoDTO                  → payment-attempts
  idIntento       String  UUID
  idOrden         String
  idCliente       String
  huellaTarjeta   String  SHA-256 hex (ver §4.2) — key del mensaje
  ultimos4        String  nullable
  monto           Long    > 0
  moneda          String  "CLP"
  pasarela        enum    WEBPAY | SIMULADA
  timestamp       long

ResultadoPagoDTO                → pagos-resultado
  idOrden         String  (buyOrder) — key
  idCliente       String  (sessionId)
  estado          enum    AUTORIZADO | RECHAZADO | ANULADO | ERROR
  monto           Long    nullable
  codigoAutorizacion String nullable
  ultimos4        String  nullable
  pasarela        enum    WEBPAY | SIMULADA
  timestamp       long

ProductoCatalogoDTO             → catalogo-productos (snapshot completo; null value = tombstone)
  id, nombre, marca, categoria, subcategoria, precio (Long), stock (Integer),
  calificacion (Double), imagenUrl, descripcion, actualizadoEn (long)
```

---

## 3. Reglas transversales para TODO servicio Spring

### 3.1 Configuración del cliente Kafka (criterio 1)

- Una clase `Config/KafkaConfig.java` por servicio con los `ProducerFactory`,
  `ConsumerFactory` y `ConcurrentKafkaListenerContainerFactory` **explícitos**. Los nombres de
  tópico y grupo vienen de `application.yaml` (`app.kafka.topicos.*`, `app.kafka.grupos.*`),
  expuestos con una clase `@ConfigurationProperties` (`Config/KafkaTopicosProperties.java`).
  Ningún nombre de tópico literal en medio de la lógica.
- `bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092,localhost:9093,localhost:9094}`.
- **Productor**: `acks=all`, `enable.idempotence=true`, `retries=10`,
  `max.in.flight.requests.per.connection=5`, `delivery.timeout.ms=30000`,
  `request.timeout.ms=10000`, `linger.ms=10`, `batch.size=32768`, `compression.type=lz4`,
  `client.id=<servicio>-producer`, key `StringSerializer`, value `JsonSerializer` con
  `JsonSerializer.ADD_TYPE_INFO_HEADERS=false` (el consumidor no depende del paquete Java del
  productor).
- **Consumidor**: key `StringDeserializer`; value **`ErrorHandlingDeserializer`** que envuelve a
  `JsonDeserializer` con `VALUE_DEFAULT_TYPE=<DTO local>`, `USE_TYPE_INFO_HEADERS=false` y
  `TRUSTED_PACKAGES` = el paquete DTO del servicio (**nunca `"*"`**);
  `enable.auto.commit=false` (ack del contenedor, `AckMode.RECORD`),
  `auto.offset.reset` según §2.2, `max.poll.records=100`, `max.poll.interval.ms=300000`,
  `session.timeout.ms=45000`, `heartbeat.interval.ms=15000`, `isolation.level=read_committed`,
  `client.id=<servicio>-consumer`.
- Un `ConsumerFactory`/container factory **por tipo de DTO** cuando el servicio consume más de uno.
- Comentario breve del *por qué* en cada propiedad no obvia (estilo del repo).

### 3.2 Productores (criterio 4)

- Una clase por tópico en `Component/` (p. ej. `ProductorIntentosPago`), con método tipado
  `publicar(IntentoPagoDTO)`. Usa `KafkaTemplate<String, DTO>`.
- `send(topico, key, dto).whenComplete(...)`: en éxito log INFO con **tópico, partición, offset
  y key**; en error log ERROR con contexto. La operación de negocio no falla si Kafka falla
  (salvo ms-pago, donde publicar el intento ES parte del flujo antifraude: ahí se responde 503).

### 3.3 Consumidores (criterio 5)

- Clase en `Component/` (p. ej. `ConsumidorIntentosPago`) con `@KafkaListener(topics =
  "#{@kafkaTopicosProperties.…}", groupId = "…", containerFactory = "…", concurrency = "…")`.
- Recibe `ConsumerRecord<String, DTO>` (o `@Payload` + `@Header(KafkaHeaders.RECEIVED_PARTITION)`,
  `OFFSET`, `RECEIVED_KEY`), loguea **tópico/partición/offset/key**, valida con
  `jakarta.validation.Validator`, y **delega en un `Service`**. Cero reglas de negocio en el listener.

### 3.4 Manejo de errores (criterio 6)

- `DefaultErrorHandler` en cada container factory con
  `DeadLetterPublishingRecoverer` (destino `<tópico>.DLT`, misma partición) y
  `ExponentialBackOffWithMaxRetries(3)` (1 s, ×2, máx 10 s).
- `addNotRetryableExceptions(DeserializationException.class, MethodArgumentNotValidException.class,
  ConstraintViolationException.class, PayloadInvalidoException.class)` → van directo al DLT
  (reintentar un JSON roto no lo arregla).
- Excepciones transitorias (`DataAccessException`, `TransientDataAccessException`, timeouts) se
  reintentan.
- `setRetryListeners(...)` loguea WARN cada reintento con número de intento, tópico, partición, offset.
- Un listener `ConsumidorDlt` por servicio (`grupo-dlt-<servicio>`) que loguea ERROR con los
  headers `kafka_dlt-original-topic`, `-partition`, `-offset`, `-exception-message`.
- Prueba obligatoria: un mensaje inválido termina en el DLT y el consumidor sigue procesando el
  siguiente (ver §3.6).

### 3.5 Convenciones de código

- Spring Boot **3.5.6**, Java **21** (`<java.version>21</java.version>`), `application.yaml`.
- Servicios existentes conservan su `groupId` (`com.kafka.*`). Servicios nuevos:
  `groupId=com.pedidos360`, paquete raíz `com.pedidos360.<servicio>` (convención del docente).
- Paquetes con mayúscula: `Config`, `Controller`, `DTO`, `Model`, `Repository`, `Service`,
  `Component` (productores/consumidores), `Exception`.
- Nombres, comentarios, logs y mensajes de error **en español**. Comentarios que explican el
  *por qué*, no el *qué*.
- Lombok permitido. En cada `pom.xml`: propiedad `<lombok.version>1.18.42</lombok.version>` y
  `maven-compiler-plugin` con `<proc>full</proc>` + `annotationProcessorPaths` (lombok, y
  `spring-boot-configuration-processor` si se usa). Sin esto **no compila en JDK ≥ 23**.
- `@RestControllerAdvice` con respuestas de error `{ "error": "...", "detalle": "...",
  "timestamp": ... }` y códigos correctos (400 validación, 404, 409 conflicto, 429, 503).
- CORS: `Config/CorsConfig.java` igual al de ms-pago, orígenes de
  `${CORS_ORIGENES:http://localhost:5173,http://localhost:5174,http://localhost:8090,http://localhost:8091}`,
  headers permitidos `Authorization, Content-Type, X-Usuario-Id`.
- Actuator expone **sólo** `health` (ver doc 05). Para servicios con Kafka, el health de Kafka
  no viene de fábrica: no hace falta replicar el indicador de ms-kafka-admin.
- `Dockerfile` multi-stage copiado de ms-pago (usuario no-root, `HEALTHCHECK` con curl a
  `/actuator/health`, puerto propio). `JAVA_TOOL_OPTIONS` se fija en el compose.
- Identidad del usuario mientras no exista Cognito: header `X-Usuario-Id`, resuelto en **un solo
  lugar** (`Component/UsuarioActual.java`), para que la fase 4 cambie sólo esa clase.

### 3.6 Pruebas y compilación (requisito "compila y responde a pruebas básicas")

- Compilar/probar con: `cmd.exe /c 'C:\Users\diego\.tools\apache-maven-3.9.11\bin\mvn.cmd -B test'`
  dentro de la carpeta del servicio (desde git-bash; el script POSIX `mvn` falla bajo MSYS).
- Tests que NO necesitan Docker:
  - `spring-kafka-test` con **`@EmbeddedKafka`** (KRaft embebido): al menos una prueba de
    integración por servicio con Kafka que publique un DTO válido y verifique que el `Service`
    lo procesa, y otra que publique un payload inválido y verifique que llega a `<tópico>.DLT`
    y que el siguiente mensaje válido se procesa igual.
  - JPA: H2 en scope test (`spring.datasource.url=jdbc:h2:mem:…` en
    `src/test/resources/application.yaml`), `ddl-auto=create-drop`.
  - Lógica de dominio (ventana antifraude, similitud, máquina de estados) con JUnit puro.
  - Controllers con `@WebMvcTest` + MockMvc para validación y códigos HTTP.
  - RabbitMQ: no hay broker embebido; probar el listener/servicio con mocks y la topología
    (`Declarables`) como beans.
- `mvn -B test` debe terminar en `BUILD SUCCESS`. **Probar que los tests pueden fallar**: romper
  a propósito una regla, ver el rojo, restaurar, y reportarlo.
- `.gitignore` por servicio no hace falta: el de la raíz cubre `target/`, IDE y credenciales.

---

## 4. ms-pago (8083) — antifraude por tarjeta, ventana real, resultado a Kafka

### 4.1 Lo que está mal hoy (verificado)

1. Cuenta por `clientId`, no por tarjeta (el enunciado dice *misma tarjeta*).
2. Compara sólo contra el último intento: no es ventana deslizante (el código lo admite).
3. Bloquea en el **3.º** intento; "más de 3 veces" significa que el **4.º** infringe.
4. Toda la regla vive dentro del `@KafkaListener`.
5. El `send` no tiene callback; no hay DLT ni reintentos; `trusted.packages: "*"`.
6. El resultado del commit de Webpay no se publica: nadie más se entera de la venta.

### 4.2 Diseño

- `PasarelaPago` (interfaz) con `PasarelaWebpay` (la actual) y `PasarelaSimulada` (aprueba
  salvo tarjetas que terminan en `0002`, que rechaza — documentado). `PeticionPagoDTO` agrega:
  `pasarela` (`WEBPAY` por defecto), `tarjeta` (`numero` 13–19 dígitos, **obligatorio si
  SIMULADA**, prohibido loguearlo), `origen` (`TIENDA` | `CONSOLA`, por defecto `CONSOLA`).
  Campos existentes `clientId`, `amount`, `transactionId` se mantienen (la consola los usa).
- **Huella de tarjeta**: `SHA-256(numeroNormalizado + pepper)` en hex, `pepper` de
  `${ANTIFRAUDE_PEPPER:pedidos360-dev}`. El número **nunca** se guarda ni se loguea; sólo
  `ultimos4`.
  - Con **WEBPAY** Transbank no entrega la tarjeta antes del pago (por diseño PCI DSS): la
    huella se calcula sobre `"cuenta:" + clientId`. Documentar esta decisión en el README y en
    un comentario en el código: la ventana y la regla son las mismas, cambia la clave.
- **Ventana deslizante real**: tabla `intento_tarjeta (id, huella, instante)`; al procesar un
  intento se cuentan los de esa huella con `instante > ahora − 10 s` **incluyéndolo**. Si el
  conteo es **> 3** → se bloquea esa huella por `${ANTIFRAUDE_BLOQUEO_MINUTOS:30}` minutos
  (tabla `bloqueo_tarjeta (huella PK, ultimos4, intentos, bloqueadoHasta, actualizadoEn)`), se
  registra `alerta_fraude (id, huella, ultimos4, idCliente, intentos, creadaEn)` y log ERROR.
  Ventana y umbral configurables (`app.antifraude.ventana-segundos=10`, `umbral=3`).
  Limpieza: un `@Scheduled` borra intentos con más de 1 h.
- Clase de dominio pura `VentanaAntifraude` (sin Spring) con la decisión, testeada con JUnit.
- **Flujo HTTP** `POST /api/pagos/iniciar`:
  1. `@Valid` → 400 con detalle.
  2. Huella bloqueada → **429** `{ message, bloqueadoHasta, intentos }` (texto actual se mantiene).
  3. Publica `IntentoPagoDTO` en `payment-attempts` (key huella). Si Kafka no confirma en 5 s → 503.
  4. WEBPAY → crea transacción con `returnUrl + "?origen=" + origen`; responde
     `{ url, token }` como hoy. SIMULADA → decide, publica `ResultadoPagoDTO` y responde
     `{ estado, idOrden, ultimos4 }` (200 aprobado / 402 rechazado).
- **Commit Webpay** (`/api/pagos/commit`, POST y GET como hoy): además de redirigir, publica
  `ResultadoPagoDTO` en `pagos-resultado`. La redirección usa `origen`:
  `TIENDA → ${FRONT_URL_TIENDA:http://localhost:5174}`, `CONSOLA → ${FRONT_URL:http://localhost:5173}`.
  Lista cerrada: nunca se redirige a una URL que venga del cliente.
- `GET /api/alertas` mantiene la forma de `ResumenAlertasDTO`/`AlertaFraudeDTO` (la consola la
  lee) y agrega `huellaTarjeta` (primeros 12 hex) y `ultimos4`. `idCliente` se mantiene.
- Consumidor `grupo-antifraude` (concurrency 3) → `ServicioAntifraude.procesarIntento(dto)`.
  `gestionarResultadoCallbackPago` (reinicio del contador en pago exitoso) se conserva sobre la
  huella.
- Tests: ventana (3 en 10 s no bloquea, el 4.º sí, 4 en 11 s no bloquea), controller (400/429),
  EmbeddedKafka (intento válido procesado, inválido → `payment-attempts.DLT`).

---

## 5. ms-recomendaciones (8084) — similitud, DynamoDB, dashboard WebSocket

### 5.1 Lo que está mal hoy (verificado)

1. Recomienda el top 5 de **los mismos** productos que el usuario ya vio, no parecidos.
2. El catálogo está hardcodeado (`ProductoConfig`, 20 productos de ropa/hogar/deportes).
3. Persiste en MySQL (no suma el +2 de DynamoDB). Nadie escribe en `EventosOperaciones`.
4. El productor publica el DTO de la petición HTTP; sin DLT, sin reintentos, `trusted "*"`.

### 5.2 Diseño

- **Eventos**: consume `EventoInteraccionDTO` (§2.3). `POST /api/interacciones` se mantiene con
  el cuerpo viejo `{clientId,itemId,action,category,productType}` (la consola lo usa) y lo
  **traduce** a `EventoInteraccionDTO` (`view→VER_PRODUCTO`, `cart→AGREGAR_CARRITO`,
  `purchase→COMPRA`, origen `ms-recomendaciones`) antes de publicar. Se agrega
  `POST /api/interacciones/simular?cantidad=50&usuarioId=` para la demo.
- **Catálogo**: `CatalogoEnMemoria` (ConcurrentHashMap) alimentado por `catalogo-productos`
  (`grupo-catalogo-recomendaciones`, earliest; tombstone = borrar). `ProductoConfig` se
  reemplaza por la semilla de §7.1 como **respaldo** mientras el tópico está vacío.
- **Perfil y similitud** (`Service/MotorRecomendaciones`, puro y testeado):
  - Pesos: `VER_PRODUCTO 1`, `APLICAR_CUPON 0.5` (sobre los productos del carrito si vienen en
    `detalle`), `GESTIONAR_ENVIO 0`, `AGREGAR_CARRITO 3`, `COMPRA 5`.
  - Decaimiento temporal: peso × `0.5^(edadHoras/24)` (lo reciente pesa más). Ventana: últimos
    100 eventos del usuario.
  - Afinidad por `categoria`, `subcategoria` y `marca` = suma de pesos normalizada.
  - Candidatos = catálogo − comprados − productos con stock 0. Score =
    `3·afCategoria + 2·afSubcategoria + 1·afMarca + 0.5·popularidadGlobal`; los vistos-sin-comprar
    entran con −20 % (se prefiere lo *parecido* a lo *ya visto*).
  - Devuelve top 5 con `motivo` legible: "Porque viste Galaxy S24", "Te interesa Audio (62 %)",
    "Marca que sueles revisar: Sony", o "Popular entre otros clientes" si no hay historial.
  - Se recalcula al consumir cada evento del usuario.
- `ProductoEnriquecidoDTO` conserva sus campos y agrega `motivo`, `subcategoria`, `stock`.
  `GET /api/recomendaciones?userId=` se mantiene.
- **DynamoDB (+2)** con AWS SDK v2 *enhanced client*:
  - `HistorialEventos` (PK `usuarioId`, SK `instante#idEvento`) — cada evento consumido.
  - `RecomendacionesUsuario` (PK `usuarioId`) — último top 5 con motivos y fecha.
  - `EventosOperaciones` (PK `tipo` + SK `ocurridoEn`, igual que `infra/terraform-telemetria/dynamodb.tf`;
    la tabla con Streams que dispara la Lambda, doc 07) — un
    snapshot de métricas cada 5 s **sólo si `app.dynamodb.publicar-operaciones=true`**.
  - Endpoint configurable: `${DYNAMODB_ENDPOINT:}` (vacío = AWS real con credenciales del
    entorno, incluidas las temporales del Learner Lab `AWS_SESSION_TOKEN`); en compose apunta a
    `http://dynamodb-local:8000`. Región `${AWS_REGION:us-east-1}`.
  - Crea las tablas al arrancar si no existen (`app.dynamodb.crear-tablas=true`).
  - Se elimina MySQL de este servicio (entidades `Interacciones`/`Recomendaciones` y sus repos).
- **Métricas y dashboard WebSocket (+3)** — `grupo-dashboard` (latest, concurrency 1) alimenta
  `Service/MetricasEnVivo` (ventanas deslizantes en memoria):
  1. interacciones por minuto (últimos 60 s, y serie de los últimos 15 min por minuto)
  2. top 5 productos más vistos (últimos 15 min)
  3. usuarios activos (distintos, últimos 5 min)
  4. embudo vistas → carrito → compra (últimos 15 min)
  5. eventos por tipo
  - WebSocket nativo (Spring `spring-boot-starter-websocket`, `TextWebSocketHandler`, sin STOMP)
    en `/ws/metricas`; cada 2 s difunde
    `{ "tipo": "METRICAS", "timestamp", "interaccionesPorMinuto", "serieMinutos":[...],
    "topProductos":[{productoId,nombre,vistas}], "usuariosActivos", "embudo":{vistas,carritos,compras},
    "porTipo":{...} }`. Orígenes permitidos = los de CORS.
  - `GET /api/metricas` devuelve el mismo snapshot (compatibilidad con el sondeo de la consola;
    revisar qué campos lee hoy la consola en `frontend/src/lib/types.ts` y mantenerlos).
- Tests: motor (parecidos > vistos, excluye comprados y sin stock, motivo), traducción del DTO
  viejo, ventanas de métricas, EmbeddedKafka con DLT, repositorio Dynamo con cliente mockeado.

---

## 6. ms-kafka-admin (8085) — de inspeccionar a administrar

Se mantiene todo lo que existe. Se agrega (cada uno con validación y `@RestControllerAdvice`):

| Verbo | Ruta | AdminClient | Validación |
|---|---|---|---|
| GET | `/api/topicos/{nombre}/configuracion` | `describeConfigs` | 404 si no existe; marca cuáles son default vs override |
| PUT | `/api/topicos/{nombre}/configuracion` | `incrementalAlterConfigs` (SET/DELETE) | lista blanca: `retention.ms`, `retention.bytes`, `cleanup.policy`, `min.insync.replicas`, `segment.bytes`, `segment.ms`, `compression.type`, `max.message.bytes`, `min.compaction.lag.ms`, `min.cleanable.dirty.ratio`; valores tipados (`min.insync.replicas` ≤ RF, `cleanup.policy ∈ {delete, compact, "compact,delete"}`) |
| POST | `/api/topicos/{nombre}/particiones` | `createPartitions` | sólo aumentar; 400 si `nueva ≤ actual` (Kafka no permite reducir) |
| GET | `/api/consumer-groups/{id}` | `describeConsumerGroups` | estado, coordinador, estrategia, miembros con su asignación tópico/partición |
| POST | `/api/consumer-groups/{id}/offsets/reset` | `alterConsumerGroupOffsets` + `listOffsets` | cuerpo `{ topico, estrategia: EARLIEST|LATEST|TIMESTAMP|OFFSET, valor? , particiones? }`; **409 si el grupo tiene miembros activos** (Kafka lo exige vacío) |
| DELETE | `/api/consumer-groups/{id}` | `deleteConsumerGroups` | 409 si no está vacío |
| GET | `/api/brokers/{id}/configuracion` | `describeConfigs(BROKER)` | |

- `POST /api/topicos` ya valida nombre/particiones/RF; agregar que `RF ≤ brokers vivos` y
  configs de la misma lista blanca.
- `infra/kafka/crear-topicos.sh` (bash, idempotente) con la tabla de §2.1 — lo invoca el compose.
  Comentar en el script el *por qué* de cada configuración (es evidencia para el criterio 7).
- Tests: `@WebMvcTest` de los endpoints nuevos (400/404/409) con el service mockeado, y unit
  tests del service con `AdminClient` mockeado (Mockito) para la lista blanca y la regla de
  "sólo aumentar particiones".

---

## 7. ms-productos (8086) — nuevo

### 7.1 Catálogo semilla (fuente de verdad única; ms-recomendaciones y la tienda usan la misma)

| id | nombre | marca | categoria (slug · etiqueta) | subcategoria | precio | stock | calif. | descripción |
|---|---|---|---|---|---|---|---|---|
| item_101 | Galaxy S24 | Samsung | celulares · Celulares | gama-alta | 649990 | 14 | 4.7 | 6.2" AMOLED, 256 GB. |
| item_102 | iPhone 15 | Apple | celulares | gama-alta | 799990 | 6 | 4.8 | Chip A16, cámara de 48 MP. |
| item_103 | Moto G84 | Motorola | celulares | gama-media | 229990 | 40 | 4.4 | Pantalla pOLED, 5G. |
| item_104 | ZenBook 14 | ASUS | laptops · Laptops | ultraliviana | 749990 | 22 | 4.6 | Ryzen 7, 16 GB, SSD 512 GB. |
| item_105 | MacBook Air M2 | Apple | laptops | ultraliviana | 1099990 | 9 | 4.9 | 13.6", 8 GB, 256 GB. |
| item_106 | IdeaPad Slim 3 | Lenovo | laptops | uso-diario | 429990 | 33 | 4.3 | Core i5, 8 GB, SSD 512 GB. |
| item_107 | WH-1000XM5 | Sony | audio · Audio | audifonos | 299990 | 18 | 4.8 | Cancelación de ruido activa. |
| item_108 | AirPods Pro 2 | Apple | audio | audifonos | 249990 | 12 | 4.7 | Audio espacial, estuche USB-C. |
| item_109 | Flip 6 | JBL | audio | parlantes | 119990 | 52 | 4.6 | Parlante portátil IP67. |
| item_110 | PlayStation 5 | Sony | gaming · Gaming | consolas | 549990 | 5 | 4.9 | Consola con lector de discos. |
| item_111 | Switch OLED | Nintendo | gaming | consolas | 329990 | 27 | 4.8 | Pantalla OLED de 7". |
| item_112 | DualSense | Sony | gaming | controles | 74990 | 70 | 4.6 | Control inalámbrico háptico. |
| item_113 | Bravia 55" 4K | Sony | tv-video · TV y video | televisores | 699990 | 10 | 4.6 | Google TV, HDR10, 120 Hz. |
| item_114 | Fire TV Stick 4K | Amazon | tv-video | streaming | 59990 | 45 | 4.4 | Streaming 4K con Alexa. |
| item_115 | Echo Dot 5 | Amazon | smart-home · Smart home | asistentes | 54990 | 38 | 4.5 | Parlante inteligente con Alexa. |
| item_116 | Hue White & Color | Philips | smart-home | iluminacion | 39990 | 60 | 4.4 | Ampolleta inteligente E27. |
| item_117 | Cargador GaN 65 W | Anker | accesorios · Accesorios | carga | 34990 | 80 | 4.7 | USB-C, carga rápida para laptop. |
| item_118 | Funda MagSafe iPhone 15 | Apple | accesorios | fundas | 49990 | 35 | 4.3 | Silicona con imanes MagSafe. |
| item_119 | Alpha a6400 | Sony | camaras · Cámaras | mirrorless | 899990 | 7 | 4.8 | 24 MP, enfoque en tiempo real. |
| item_120 | HERO12 Black | GoPro | camaras | accion | 429990 | 15 | 4.7 | 5.3K, sumergible 10 m. |

`imagenUrl` = `null` por ahora (la tienda muestra un placeholder deliberado).

### 7.2 Contrato

- Entidad `Producto` (campos de §7.1 + `actualizadoEn`), `@Version` para concurrencia optimista
  del stock. Semilla con `CommandLineRunner` si la tabla está vacía.
- `GET /api/productos?categoria=&q=&orden=relevancia|precio_asc|precio_desc` → lista.
- `GET /api/productos/categorias` → `[{slug, etiqueta, cantidad}]`.
- `GET /api/productos/{id}` (header `X-Usuario-Id` opcional) → detalle **y publica
  `VER_PRODUCTO`** en `interacciones-user` si viene usuario.
- `POST /api/productos/stock/reservas` `{ idPedido, items:[{productoId,cantidad}] }` → 200 o
  **409** `{ error, faltantes:[{productoId, disponible}] }`. Todo o nada (transaccional).
- `POST /api/productos/stock/liberaciones` (mismo cuerpo) → devuelve stock (idempotente por
  `idPedido`: tabla `reserva_stock`).
- `PUT /api/productos/{id}/stock` `{ cantidad }` (reponer, admin) → 200.
- `GET /api/productos/admin/stock` → `[{id,nombre,stock,nivel: AGOTADO|BAJO|NORMAL}]` (≤ 10 = BAJO).
- Cada cambio de stock/precio publica el snapshot en `catalogo-productos`; al arrancar
  (`ApplicationReadyEvent`) publica el catálogo completo.
- Tests: reservas (todo o nada, 409), liberación idempotente, publicación de VER_PRODUCTO con
  EmbeddedKafka, controller.

---

## 8. ms-carrito (8087) — nuevo: carrito, cupones, envío y pedidos

- Entidades: `Carrito (usuarioId PK, cupon, metodoEnvio, direccion, comuna, sucursal, actualizadoEn)`,
  `ItemCarrito (id, carrito, productoId, nombre, precioUnitario, cantidad)`,
  `Pedido (id "P-<n>", usuarioId, estado, metodoEnvio ENVIO|RETIRO, destino, subtotal, descuento,
  costoEnvio, total, cupon, creadoEn, actualizadoEn)`, `ItemPedido`,
  `Cupon (codigo PK, tipo PORCENTAJE|MONTO, valor, minimoCompra, activo)`,
  `HistorialEstadoPedido (id, pedido, estado, instante)`.
- Semilla de cupones: `BIENVENIDA10` (10 %), `ENVIOGRATIS` (descuenta el envío), `GAMER5000`
  (5 000 en compras ≥ 50 000).
- Envío: `ENVIO` cuesta 3 990, gratis con subtotal ≥ 100 000; `RETIRO` gratis en sucursal
  (`Santiago Centro · Huérfanos 1160`, `Providencia · Av. Providencia 2124`,
  `Maipú · Av. Pajaritos 3450`).
- Precios y nombres se toman de ms-productos (`GET /api/productos/{id}`) vía `RestClient` con
  `${PRODUCTOS_URL:http://localhost:8086}`; el cliente nunca fija precios.
- Endpoints (usuario por `X-Usuario-Id`):
  - `GET /api/carrito` → carrito con totales calculados (subtotal, descuento, envío, total).
  - `POST /api/carrito/items` `{productoId, cantidad}` → publica **AGREGAR_CARRITO** (Kafka) +
    notificación `carrito.producto-agregado` (RabbitMQ).
  - `PATCH /api/carrito/items/{productoId}` `{cantidad}` (0 = quitar) · `DELETE /api/carrito`.
  - `POST /api/carrito/cupon` `{codigo}` → 200 / 404 / 422 (mínimo no alcanzado) → publica
    **APLICAR_CUPON** (`detalle.cupon`, `detalle.productos`). `DELETE /api/carrito/cupon`.
  - `PUT /api/carrito/envio` `{metodo, direccion?, comuna?, sucursal?}` → publica
    **GESTIONAR_ENVIO** (`detalle.metodoEnvio`, `detalle.comuna`).
  - `POST /api/pedidos` → valida carrito no vacío y datos de envío; **reserva stock** en
    ms-productos (409 se propaga con faltantes); crea `Pedido` en `PENDIENTE_PAGO`; vacía el
    carrito; notificación `pedido.creado`. Responde el pedido (la tienda luego llama a
    `ms-pago /api/pagos/iniciar` con `transactionId = idPedido`, `amount = total`,
    `clientId = usuarioId`, `origen = TIENDA`).
  - `GET /api/pedidos` (los del usuario, con historial de estados) · `GET /api/pedidos/{id}`.
  - `GET /api/admin/resumen` → `{ ventasPorDia:[{fecha,total}] (14 d), masVendidos:[{productoId,
    nombre,unidades}], ventasPorCategoria:[{categoria,total}], pedidosActivos, ingresosTotales,
    unidadesVendidas }` (sólo pedidos pagados en adelante). `GET /api/admin/pedidos`.
- Consumidor `grupo-carrito` (concurrency 3) de `pagos-resultado` → `ServicioPedidos`:
  - `AUTORIZADO` → `PAGADO`; publica **COMPRA** por cada ítem en `interacciones-user`;
    notificación `pedido.pagado`.
  - `RECHAZADO`/`ANULADO`/`ERROR` → `PAGO_RECHAZADO`; libera stock en ms-productos;
    notificación `pedido.rechazado`.
  - Idempotente: un resultado repetido para un pedido ya resuelto se ignora con log.
- Máquina de estados (clase pura, testeada):
  `PENDIENTE_PAGO → PAGADO → PREPARANDO → ENVIADO → EN_CAMINO → ENTREGADO` (envío) y
  `PENDIENTE_PAGO → PAGADO → PREPARANDO → LISTO_RETIRO → RETIRADO` (retiro);
  `PENDIENTE_PAGO → PAGO_RECHAZADO`. Transición inválida → excepción.
- `@Scheduled(fixedDelayString = "${app.envios.avance-ms:20000}")` avanza un paso los pedidos
  en curso y publica `envio.estado-actualizado` en RabbitMQ (simula la logística; documentarlo).
- RabbitMQ: ver §9 (productor con publisher confirms y returns).
- Tests: totales/cupones/envío (puro), máquina de estados, consumidor con EmbeddedKafka y DLT,
  controller, productor Rabbit con `RabbitTemplate` mockeado.

---

## 9. RabbitMQ — topología, mensajes y garantías

- **Exchange** `pedidos360.notificaciones` (topic, durable).
- **Routing keys**: `carrito.producto-agregado`, `pedido.creado`, `pedido.pagado`,
  `pedido.rechazado`, `envio.estado-actualizado`.
- **Colas** (todas **quorum**, replicadas en los 3 nodos):
  - `notificaciones.cliente` ← bindings `carrito.*`, `pedido.*`, `envio.*`; argumentos
    `x-queue-type=quorum`, `x-dead-letter-exchange=pedidos360.notificaciones.dlx`,
    `x-dead-letter-strategy=at-least-once`, `x-overflow=reject-publish`, `x-delivery-limit=5`.
  - `notificaciones.auditoria` ← binding `#` (todo); demuestra el ruteo por patrón.
  - `notificaciones.cliente.dlq` ← exchange `pedidos360.notificaciones.dlx` (fanout).
- **Mensaje** `NotificacionDTO`: `idNotificacion` (UUID), `usuarioId`, `tipo` (=routing key),
  `titulo`, `mensaje`, `idPedido` (nullable), `datos` (Map), `timestamp`. JSON con
  `Jackson2JsonMessageConverter`; `messageId` y `contentType` en las propiedades.
- **Productor** (ms-carrito): `publisher-confirm-type: correlated`, `publisher-returns: true`,
  `template.mandatory: true`; log de confirmación (ack/nack) con `correlationId`, y de mensajes
  devueltos (sin ruta) con la routing key. Mensajes `PERSISTENT`.
- **Consumidor** (ms-notificaciones): `acknowledge-mode: auto`, reintento stateless
  (`retry.enabled=true`, 3 intentos, backoff 1 s ×2) y luego `RejectAndDontRequeueRecoverer` →
  DLX → DLQ. Listener de la DLQ: log ERROR con los headers `x-death` (cola origen, motivo,
  conteo) y lo guarda en la tabla `mensaje_fallido`.
- Conexión con failover: `spring.rabbitmq.addresses=${RABBITMQ_ADDRESSES:localhost:5672,localhost:5673,localhost:5674}`,
  usuario/clave de `${RABBITMQ_USER:pedidos360}` / `${RABBITMQ_PASS:pedidos360}`.
- La topología se declara como `Declarables` en **ambos** lados (productor y consumidor): el
  que arranque primero la crea, y redeclarar con los mismos argumentos es inocuo.

## 10. ms-notificaciones (8088) — nuevo

- `@RabbitListener(queues = "notificaciones.cliente")` → valida → `ServicioNotificaciones`:
  persiste `Notificacion (id, usuarioId, tipo, titulo, mensaje, idPedido, leida, creadaEn)` y la
  empuja por **SSE** a los navegadores del usuario.
- `@RabbitListener(queues = "notificaciones.auditoria")` → log INFO de auditoría.
- `@RabbitListener(queues = "notificaciones.cliente.dlq")` → log ERROR + `mensaje_fallido`.
- Endpoints: `GET /api/notificaciones?usuarioId=` (más recientes primero, 50),
  `GET /api/notificaciones/stream?usuarioId=` (SSE, `text/event-stream`, heartbeat cada 20 s;
  por query porque `EventSource` no manda headers), `PATCH /api/notificaciones/{id}/leida`,
  `POST /api/notificaciones/leidas?usuarioId=`, `GET /api/notificaciones/fallidas` (admin).
- Payload inválido (sin `usuarioId`, tipo desconocido) → `AmqpRejectAndDontRequeueException`
  directo a DLQ (no se reintenta lo que no tiene arreglo).
- Tests: servicio (persistencia + emisión SSE), listener con payload inválido, DLQ logging.

## 11. ms-rabbit-admin (8089) — nuevo

- `AmqpAdmin`/`RabbitAdmin` para declarar y borrar; **API HTTP de management** (`RestClient`
  con basic auth a `${RABBITMQ_MGMT_URL:http://localhost:15672}`) para listar.
- `GET/POST /api/rabbit/colas`, `DELETE /api/rabbit/colas/{nombre}` (`?siVacia=true`) ·
  `GET/POST /api/rabbit/exchanges`, `DELETE /api/rabbit/exchanges/{nombre}` ·
  `GET/POST /api/rabbit/bindings`, `DELETE /api/rabbit/bindings` (cuerpo con origen, destino,
  routing key) · `GET /api/rabbit/cluster` (nodos, si están corriendo, memoria, particiones de red)
  · `POST /api/rabbit/colas/{nombre}/purgar`.
- Validación: nombres no vacíos, tipo de exchange ∈ {direct, topic, fanout, headers}, tipo de
  cola ∈ {quorum, classic}, no permitir borrar las colas/exchanges `amq.*` ni los del sistema
  sin `?forzar=true`.
- Tests: `@WebMvcTest` (validaciones/códigos) y service con `AmqpAdmin`/`RestClient` mockeados.

## 12. Fase 4 — Cognito + JWT (contrato compartido por backend, tienda, consola y Terraform)

**Pool** (Terraform, `infra/terraform-telemetria/` o carpeta hermana): grupos `admin` y `cliente`;
login por email; app client **público** (sin secreto), OAuth *authorization code* + **PKCE**,
scopes `openid email profile`; dominio de Hosted UI; callback URLs
`http://localhost:{5173,5174,8090,8091}/auth/callback`, logout URLs = esos cuatro orígenes.
Usuarios semilla: `ana@correo.cl` (cliente) y `admin@pedidos360.cl` (admin) con clave temporal.

**Qué token viaja y qué identidad sale de él** (decisión, aplicar igual en todas partes):
- El front envía el **ID token** como `Authorization: Bearer <id_token>`: es el que trae `email`
  y `cognito:groups`. El access token de Cognito no trae `email`.
- `usuarioId` = claim `email` (coincide con las cuentas demo y con los datos ya generados).
- Roles: `cognito:groups` → `ROLE_ADMIN` / `ROLE_CLIENTE`.
- `EventSource` (SSE) no manda headers: **sólo** en `GET /api/notificaciones/stream` el token
  se acepta por query `?access_token=` (BearerTokenResolver con `allowUriQueryParameter` limitado
  a esa ruta).

**Backend** (los 7 servicios): `spring-boot-starter-oauth2-resource-server`.
- `app.seguridad.habilitada` = `${SEGURIDAD_HABILITADA:false}`. Con `false` todo queda permitido
  como hoy y se loguea **WARN** al arrancar ("seguridad deshabilitada"). Con `true`:
- Decoder con **`jwk-set-uri`** `https://cognito-idp.${COGNITO_REGION}.amazonaws.com/${COGNITO_USER_POOL_ID}/.well-known/jwks.json`
  (no `issuer-uri`: ese descarga la discovery al arrancar y el servicio no levanta sin red) +
  validadores: firma, expiración, `iss` = `https://cognito-idp.<region>.amazonaws.com/<pool>`,
  `aud` = `${COGNITO_CLIENT_ID}`, `token_use` = `id`.
- `UsuarioActual` (una sola clase por servicio): con seguridad, `email` del JWT e **ignora**
  `X-Usuario-Id`; sin seguridad, `X-Usuario-Id` como hoy.
- Reglas:
  - **Públicas**: `OPTIONS /**`, `/actuator/health`, `GET /api/productos/**` (salvo `/admin/**`),
    `GET /api/carrito/sucursales`, `/api/pagos/commit` (lo llama Transbank), `GET /api/metricas`,
    `/ws/metricas`, `POST /api/productos/stock/reservas|liberaciones` (servidor a servidor desde
    ms-carrito, incluso desde su consumidor Kafka, sin usuario; en AWS se protege por red — dejarlo
    comentado y documentado).
  - **ADMIN**: `/api/admin/**`, `/api/productos/admin/**`, `PUT /api/productos/*/stock`,
    `GET /api/alertas`, `GET /api/notificaciones/fallidas`, todo ms-kafka-admin y ms-rabbit-admin.
  - **Autenticado** (cualquier grupo): el resto (carrito, pedidos, `POST /api/pagos/iniciar`,
    recomendaciones, interacciones, notificaciones).
  - 401/403 con el mismo JSON de error del servicio, y CORS antes que seguridad (el preflight no
    lleva token).
- Variables: `SEGURIDAD_HABILITADA`, `COGNITO_REGION`, `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`.
- Tests: con un JWT firmado por una clave RSA de prueba (decoder de test con la clave pública):
  sin token 401, token de cliente en ruta admin 403, token válido 200, `aud`/`iss` errados 401,
  `UsuarioActual` toma el email del token aunque venga otro `X-Usuario-Id`. Con
  `SEGURIDAD_HABILITADA=false` los tests existentes siguen verdes sin cambios.

**Frontends** (tienda y consola): config de runtime `COGNITO_DOMAIN`
(`https://<prefijo>.auth.<region>.amazoncognito.com`), `COGNITO_CLIENT_ID`, `COGNITO_REGION`.
- Si `COGNITO_DOMAIN` está vacío → login demo actual, con un aviso visible "modo demo sin Cognito".
- Si está → Hosted UI con authorization code + PKCE (sin librerías pesadas: `crypto.subtle`
  para el challenge, `fetch` al `/oauth2/token`), ruta `/auth/callback`, tokens en
  `sessionStorage`, logout por `/logout?client_id=…&logout_uri=…`, renovación con refresh token
  o re-login al expirar. Grupos desde el ID token para mostrar/ocultar admin.
- Todas las llamadas llevan `Authorization: Bearer <id_token>` (y siguen mandando
  `X-Usuario-Id` por compatibilidad con el backend sin seguridad).
- La consola exige grupo `admin` cuando Cognito está configurado.

## 13. Infra y compose (lo integra el agente principal, no los subagentes)

- `infra/rabbitmq/docker-compose.yml` (clúster de 3 nodos, standalone) + `infra/rabbitmq/rabbitmq.conf`
  (`cluster_formation.peer_discovery_backend = classic_config`).
- `docker-compose.yml` raíz: agrega rabbitmq1-3, dynamodb-local, ms-productos, ms-carrito,
  ms-notificaciones, ms-rabbit-admin, tienda (`../pedidos360-tienda` o `../tienda`); MySQL crea
  las bases nuevas (`infra/mysql/init.sql`); `CORS_ORIGENES` con 5173, 5174, 8090, 8091;
  límites de memoria (`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`, `mem_limit: 512m` por
  servicio, `KAFKA_HEAP_OPTS=-Xms256m -Xmx512m`).
- `infra/validar-compose.py` se extiende a los servicios nuevos.

## 14. Calendario

| Día | Entregable |
|---|---|
| Dom 22 | Contrato (este documento). Arrancan en paralelo: ms-pago, ms-recomendaciones, ms-kafka-admin, ms-productos, ms-carrito, notificaciones + rabbit-admin, tienda React |
| Lun 23 | Servicios compilando con tests verdes; compose integrado; **Docker instalado** y primer `up` |
| Mar 24 | Flujo completo en el navegador: tienda → carrito → Webpay → pedido pagado → notificación → recomendación |
| Mié 25 | Consola: dashboard WS, alertas por tarjeta, admin Kafka/Rabbit; panel admin de la tienda con datos reales |
| Jue 26 | Cognito/JWT; DynamoDB real + Lambda en Learner Lab si alcanza; evidencias; bitácora; ensayo |
| Vie 27 | Entrega |

**Línea de corte** si falta tiempo (en este orden): Lambda en AWS → Cognito → panel admin de la
tienda. No se corta: criterios de la pauta, antifraude, RabbitMQ con DLQ, flujo de compra.
