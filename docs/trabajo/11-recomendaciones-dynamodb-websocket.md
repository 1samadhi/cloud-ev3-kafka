# 11 · Recomendaciones de productos parecidos, en DynamoDB, y métricas por WebSocket

Commit `69874d1` (ms-recomendaciones). Contrato: `PLAN-INTEGRACION-EV3.md` §5.
Productores de `interacciones-user`: ms-productos (`VER_PRODUCTO`), ms-carrito
(`AGREGAR_CARRITO`, `APLICAR_CUPON`, `GESTIONAR_ENVIO`, `COMPRA`) y este mismo servicio.
El pipeline de Lambdas que lee `EventosOperaciones` está en el doc 07.

## El problema

§5.1 del contrato, verificado contra el código anterior:

1. **Recomendaba lo mismo que el usuario ya había visto**: un top 5 de sus propios
   productos más frecuentes. El enunciado pide productos **parecidos** a lo consultado.
2. El catálogo estaba escrito en `Config/ProductoConfig.java` (20 productos de ropa, hogar
   y deportes) y no coincidía con el de la tienda.
3. Persistía en MySQL (`Model/Interacciones`, `Model/Recomendaciones`), en la misma
   `pagos_db` que ms-pago, y no sumaba el +2 de DynamoDB.
4. El productor publicaba el DTO de la petición HTTP tal cual; sin DLT, sin reintentos,
   `trusted.packages: "*"`.
5. Las métricas de la consola salían de consultas a MySQL por sondeo; no había WebSocket.

Lo primero era silencioso porque la lista nunca venía vacía: un usuario que miró audífonos
recibía audífonos, y eso *parece* una recomendación.

## Qué se hizo

```
interacciones-user (6 part.) ──grupo-recomendaciones (3 hilos, earliest)──▶ ConsumidorInteracciones
        │                                                                     └▶ ServicioRecomendaciones.procesarEvento
        │                                                                          HistorialEventos (DynamoDB) ─▶ MotorRecomendaciones ─▶ RecomendacionesUsuario (DynamoDB)
        └──────────grupo-dashboard (1 hilo, latest)─────────────────────────▶ ConsumidorDashboard ─▶ MetricasEnVivo
                                                                                                        ├▶ DifusorMetricasWebSocket  /ws/metricas cada 2 s
                                                                                                        ├▶ GET /api/metricas
                                                                                                        └▶ PublicadorOperaciones ─▶ EventosOperaciones (sólo si DYNAMODB_PUBLICAR_OPERACIONES=true)
catalogo-productos (compact) ──grupo-catalogo-recomendaciones (earliest)──▶ ConsumidorCatalogo ─▶ CatalogoEnMemoria
interacciones-user.DLT + catalogo-productos.DLT ──grupo-dlt-recomendaciones──▶ ConsumidorDlt ─▶ ServicioMensajesMuertos

POST /api/interacciones (cuerpo viejo de la consola) ─▶ TraductorInteracciones ─▶ ProductorInteracciones ─▶ interacciones-user   (202)
POST /api/interacciones/simular?cantidad=&usuarioId= ─▶ SimuladorInteracciones (tope 500)
GET  /api/recomendaciones?userId=                     ─▶ último top guardado, o calculado al vuelo sin persistir
```

Clases bajo `ms-recomendaciones/src/main/java/com/kafka/recomendaciones/`. Se borraron
`ProductoConfig`, `ProductorConfig`, `InteraccionComponent`, las entidades JPA y sus
repositorios, `MetricasService` y `RecomendacionesService`.

## Decisiones y su porqué

| Decisión | Porqué | Pauta |
|---|---|---|
| `MotorRecomendaciones` arma un **perfil de afinidad** por categoría, subcategoría y marca y puntúa cada candidato: `3·cat + 2·subcat + 1·marca + 0.5·popularidad` | así se recomienda lo *parecido* a lo consultado. Los pesos y la fórmula son los de §5.2, tal cual | Enunciado recomendaciones |
| Peso por tipo de evento: `VER 1`, `CUPON 0.5`, `ENVIO 0`, `CARRITO 3`, `COMPRA 5`, con decaimiento `0.5^(edadHoras/24)` sobre los últimos 100 eventos | comprar dice más que mirar; gestionar el envío no dice nada del gusto. La sesión de hoy manda sobre la de ayer | Enunciado recomendaciones |
| Excluye lo comprado y lo que tiene stock 0; lo ya interactuado sin comprar entra con −20 % (`FACTOR_YA_VISTO = 0.8`) | no se recomienda lo que no se puede vender ni lo que ya se tiene; se prefiere lo parecido a lo repetido | Enunciado recomendaciones |
| Cada recomendación trae `motivo` ("Porque viste WH-1000XM5", "Te interesa Audio (62 %)", "Marca que sueles revisar: Sony", "Popular entre otros clientes") | en la defensa hay que poder explicar **por qué** salió cada producto; el motivo más concreto (un producto de la misma subcategoría) gana | Enunciado recomendaciones |
| `APLICAR_CUPON` reparte su 0.5 entre los productos de `detalle.productos` | el evento de cupón no trae `productoId`; ms-carrito manda los del carrito separados por coma (`MotorRecomendaciones.productosDe`) | Pauta 2 |
| El motor es puro: historial, catálogo, popularidad y "ahora" entran por parámetro | se prueba con JUnit sin Kafka ni DynamoDB y el resultado es reproducible | Pauta 5 |
| El catálogo se reconstruye desde `catalogo-productos` (compactado, `earliest`) en `CatalogoEnMemoria`; la semilla de §7.1 (`CatalogoSemilla`) sólo se usa mientras el tópico no entregó nada | el motor recorre el catálogo completo en cada evento: una llamada REST por evento ataría la disponibilidad de las recomendaciones a la de ms-productos. Un snapshot más viejo que el guardado se ignora (el catálogo nunca retrocede tras un reproceso); valor nulo con key = tombstone = producto borrado | Pauta 7, Pauta 8 |
| Historial y recomendaciones en **DynamoDB** con el *enhanced client* del SDK v2; MySQL se elimina del servicio | suma el +2 y separa la base que compartía con ms-pago. `HistorialEventos`: PK `usuarioId`, SK `instanteEvento` = `%013d#idEvento`, así "los últimos 100" es un `Query` descendente con límite, sin `Scan`, y reprocesar el mismo evento reescribe el mismo ítem | +2 DynamoDB |
| `RecomendacionesUsuario` guarda el top embebido en un solo ítem por usuario (`putItem`) | el top anterior desaparece en la misma escritura; `GET` es un `getItem` | +2 DynamoDB |
| Endpoint configurable (`DYNAMODB_ENDPOINT`): con valor → DynamoDB Local con credenciales del entorno o, si no hay, `local/local`; vacío → AWS real con la cadena por defecto (incluye `AWS_SESSION_TOKEN` del Learner Lab) | el mismo jar corre en el compose y en el lab. En el compose la variable va **sin `:`** (`${DYNAMODB_ENDPOINT-…}`) para que un `.env` con el valor vacío llegue vacío y no se reemplace por el de Local | +2 DynamoDB |
| `InicializadorTablasDynamo` crea las tablas en `afterPropertiesSet` (antes de que arranquen los listeners), `PAY_PER_REQUEST`; `EventosOperaciones` con Stream `NEW_IMAGE` y TTL `expiraEn` | si corriera después, el primer evento caería en una tabla inexistente y gastaría reintentos. Si DynamoDB no responde loguea ERROR y el servicio sigue: métricas y WebSocket no dependen de DynamoDB, y los eventos que fallen terminan en el DLT. Usa el cliente de bajo nivel porque el enhanced no expone billing, Streams ni TTL | +2 DynamoDB |
| Con `app.dynamodb.habilitado=false` se activa `RepositoriosEnMemoria` (mismo contrato: idempotencia por `idEvento`, más reciente primero) | las pruebas levantan el contexto completo sin AWS ni DynamoDB Local; loguea WARN porque todo se pierde al reiniciar | — |
| Las excepciones de DynamoDB **no** se atrapan en `ServicioRecomendaciones` | suben al contenedor, que reintenta con backoff y termina en el DLT; atraparlas haría avanzar el offset con el evento perdido. La popularidad se suma al final para que un reintento no la cuente dos veces | Pauta 6 |
| **Dos grupos sobre el mismo tópico**: `grupo-recomendaciones` (3 hilos, `earliest`) y `grupo-dashboard` (1 hilo, `latest`) | reparto: 3 hilos se dividen 6 particiones; fan-out: otro grupo tiene offsets propios y recibe todo. El dashboard sólo quiere lo que pasa ahora y un hilo evita contención sobre las ventanas | Pauta 8 |
| **Sólo `grupo-recomendaciones` publica en `interacciones-user.DLT`**; `fabricaListenerDashboard` descarta con WARN y sin reintentos | los dos grupos reciben exactamente los mismos mensajes: si ambos publicaran, cada mensaje roto aparecería **dos veces** en el DLT. Contar en memoria no tiene fallas transitorias | Pauta 6, Pauta 8 |
| `KafkaConfig` declara una `ConsumerFactory` por DTO/grupo (interacciones earliest, interacciones latest, catálogo, DLT como texto) y la plantilla del DLT con `DelegatingByTypeSerializer` | una sola fábrica no puede tener dos `auto.offset.reset`; el DLT se lee como texto porque lo que hay ahí pudo no ser JSON. Misma trampa que ms-pago: `ADD_TYPE_INFO_HEADERS` por propiedad **y** por setter hacía fallar toda publicación al DLT (lo destapó `FlujoKafkaIntegracionTest`) | Pauta 1, Pauta 4, Pauta 6 |
| Listeners que sólo loguean tópico/partición/offset/key, validan (`ValidadorMensajes`) y delegan; `PayloadInvalidoException` no se reintenta | cero reglas en la mensajería; un DTO inválido va directo al DLT en vez de gastar tres reintentos | Pauta 5, Pauta 6 |
| `ProductorInteracciones`: key `usuarioId`, callback con tópico/partición/offset, **no** lanza si Kafka falla; `max.block.ms=5000` | el perfil necesita los eventos de un usuario en orden; una interacción perdida no justifica romper la pantalla, y sin el tope un POST quedaría colgado 60 s esperando metadata | Pauta 4, contrato §3.2 |
| `POST /api/interacciones` conserva el cuerpo viejo `{clientId,itemId,action,category,productType}` y lo traduce (`TraductorInteracciones`); responde **202** | la consola lo usa; por Kafka sólo viaja el DTO del contrato. Los atributos salen del catálogo si el producto existe (la consola manda categorías de su mapa viejo). 202 porque las recomendaciones se recalculan después, al consumir | Pauta 2 |
| **WebSocket nativo** (`TextWebSocketHandler`, sin STOMP ni SockJS) en `/ws/metricas`, con los mismos orígenes que CORS | el canal es sólo de bajada; no hay destinos que rutear. El handshake desde un origen ajeno recibe 403 | +3 WebSocket |
| `MetricasEnVivo`: 5 métricas en ventanas deslizantes en memoria (int/min de los últimos 60 s + serie de 15 min, top 5 vistos en 15 min, usuarios activos en 5 min, embudo vistas→carrito→compra, eventos por tipo) | una consulta a una base por cada refresco de 2 s sería costo y latencia para datos que ya pasaron por el proceso. Cuenta con la hora de **recepción**: el tablero mide lo que se observa ahora | +3 WebSocket |
| `DifusorMetricasWebSocket` serializa el snapshot **una vez** por ciclo, envía uno inmediato al conectar, y envuelve cada sesión en `ConcurrentWebSocketSessionDecorator` (5 s / 512 KB) | el envío inicial (hilo HTTP) y el programado (scheduler) pueden coincidir y una sesión de Tomcat no admite dos envíos simultáneos; un cliente lento se corta en vez de frenar a todos | +3 WebSocket |
| `GET /api/metricas` devuelve el mismo snapshot y conserva `totalInteracciones` y `productosMasVistos` | compatibilidad con el sondeo que la consola ya hacía | — |
| `PublicadorOperaciones` escribe en `EventosOperaciones` cada 5 s **sólo** con `DYNAMODB_PUBLICAR_OPERACIONES=true` y sólo si la firma de las métricas cambió | cada escritura dispara la Lambda por Streams y cada `postToConnection` se cobra; con la tienda quieta no se paga nada. Si la escritura falla, la firma no se actualiza y el siguiente ciclo reintenta | +3 WebSocket (vía AWS, doc 07) |

## Verificación

| Qué | Resultado |
|---|---|
| `mvn -B test` en `ms-recomendaciones` | BUILD SUCCESS, **39 tests**, sin AWS (repositorios en memoria en el contexto; cliente DynamoDB mockeado en `RepositoriosDynamoTest` e `InicializadorTablasDynamoTest`) |
| `MotorRecomendacionesTest` | lo parecido supera a lo ya visto; excluye comprados (aunque también se hayan visto) y sin stock; motivos por categoría y marca; sin historial → popular; lo reciente pesa más; cupón reparte entre productos del carrito y envío no suma |
| `FlujoKafkaIntegracionTest` (Kafka embebido, 3 particiones) | grupos con la concurrencia del contrato (3 y 1); un JSON roto y uno sin `usuarioId` llegan a `interacciones-user.DLT` (**exactamente 2**: el dashboard no duplica) con los bytes originales intactos, y el válido que venía detrás produce `item_108` con motivo "Porque viste WH-1000XM5"; el POST viejo de la consola recalcula el top (sin el producto comprado) y las métricas |
| `FlujoKafkaIntegracionTest.webSocketEmpujaElSnapshot…` | snapshot inmediato + uno programado con los 9 campos; handshake desde `http://sitio-malicioso.example` → **403** |
| `RepositoriosDynamoTest` | SK ordenable y sin mapa vacío; query descendente con límite; snapshot a `EventosOperaciones` con las claves del Terraform y TTL en segundos |
| Mutante, en una copia del servicio fuera del repo (22-nov): se quitó `\|\| !p.tieneStock()` del filtro de candidatos en `MotorRecomendaciones.recomendar` | BUILD FAILURE, **39 tests, 1 falla**: `MotorRecomendacionesTest.excluyeProductosSinStock` |
| Prueba de humo `infra/prueba-humo.py` contra el sistema real (22-nov, 22 contenedores sanos), dos corridas de 25/25 | `VER_PRODUCTO` publicado por ms-productos y consumido aquí; recomendaciones personalizadas tras navegar ("AirPods Pro 2 — Porque viste WH-1000XM5"); `GET /api/metricas` con tráfico |
| Clúster real | `kafka-topics --describe`: `interacciones-user` con 6 particiones y lz4, `catalogo-productos` compactado; 8 consumer groups activos; `ms-kafka-admin` describe `grupo-recomendaciones` con **3 miembros** |
| Navegador | el dashboard de la consola se conectó a `ws://localhost:8084/ws/metricas` y mostró interacciones/min, usuarios activos y top vistos en vivo |

### No verificado

- **DynamoDB real de AWS.** Sólo se corrió contra DynamoDB Local (`amazon/dynamodb-local`,
  `-inMemory`) en el compose. Ni las credenciales del Learner Lab ni el endpoint vacío se
  probaron.
- **`PublicadorOperaciones` contra una tabla con Stream real y la Lambda del doc 07.** En el
  compose corre con `DYNAMODB_PUBLICAR_OPERACIONES=false`; sólo lo cubre
  `PublicadorOperacionesTest` con el repositorio mockeado. Lambdas y API Gateway no están
  desplegados.
- **El DLT contra Kafka real.** Los tópicos `.DLT` existen con la configuración del
  contrato, pero ningún mensaje roto se publicó contra los brokers reales; sólo con
  `@EmbeddedKafka`.
- La reconstrucción del catálogo tras un **tombstone real** de ms-productos: el camino está
  en `ConsumidorCatalogo`, pero ms-productos no borra productos y ninguna prueba lo ejerce.

## Pendiente

- El pendiente "Ningún microservicio escribe en `EventosOperaciones`" del `README.md` de
  esta carpeta queda resuelto en código (`PublicadorOperaciones`) pero apagado por defecto;
  se enciende con `DYNAMODB_PUBLICAR_OPERACIONES=true` cuando la Lambda esté desplegada.
- El nombre de la tabla por defecto es `EventosOperaciones-desarrollo` (el que crea
  `infra/terraform-telemetria` para el entorno `desarrollo`). En otro entorno hay que fijar
  `DYNAMODB_TABLA_OPERACIONES`.
- **Dos diferencias con el contrato**, ninguna rompe nada pero conviene alinear el texto:
  - §5.2 dice `EventosOperaciones (PK id)`; el código usa PK `tipo` + SK `ocurridoEn` (y
    `id` como atributo), que es la forma de `infra/terraform-telemetria/dynamodb.tf` que lee
    la Lambda. Manda el código; hay que corregir §5.2.
  - §2.2 nombra el grupo del DLT `grupo-dlt-<servicio>`; ms-pago usa `grupo-dlt-ms-pago` y
    este servicio `grupo-dlt-recomendaciones` (sin `ms-`). Se cambia en
    `app.kafka.grupos.dlt` del `application.yaml`.

## Trampas para quien siga

- DynamoDB Local corre con `-inMemory`: si su contenedor se reinicia con ms-recomendaciones
  arriba, las tablas desaparecen y **no** se recrean hasta reiniciar ms-recomendaciones
  (`InicializadorTablasDynamo` corre sólo al arrancar). Mientras tanto cada evento falla,
  agota los reintentos y termina en `interacciones-user.DLT`.
- Las métricas y la popularidad viven **en memoria por instancia**. Con dos réplicas,
  `grupo-dashboard` reparte las particiones entre ellas y cada una muestra sólo una parte
  del tráfico. Para escalar el dashboard hay que agregar en otro lado (por ejemplo, la
  tabla `EventosOperaciones`).
- `grupo-dashboard` usa `latest`: tras un reinicio el tablero empieza en cero. Es lo
  pedido, no un fallo.
- Si todos los productos del tópico llegaran por tombstone, `CatalogoEnMemoria` vuelve a la
  semilla (`usandoSemilla()` es "el mapa del tópico está vacío").
- Si se agrega un consumidor nuevo sobre `interacciones-user`, que **no** publique en el
  DLT: el dueño es `grupo-recomendaciones`.
- La lista de 5 métricas del contrato se cumple **y** el snapshot trae dos campos más
  (`totalInteracciones`, `productosMasVistos`) que sólo existen por compatibilidad con la
  consola vieja. No quitarlos sin revisar el `types.ts` del frontend.
