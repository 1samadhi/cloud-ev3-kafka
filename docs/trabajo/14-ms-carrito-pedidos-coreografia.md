# 14 · El pedido se coreografía: carrito, cupones, envío y pago por eventos

Commit `19e2dfe` (ms-carrito, servicio nuevo, puerto 8087). Contrato:
`PLAN-INTEGRACION-EV3.md` §8 (servicio) y §9 (productor RabbitMQ). Depende de ms-productos
(doc 13) por REST y de ms-pago (doc 10) por `pagos-resultado`; sus notificaciones las consume
ms-notificaciones (doc 15).

## El problema

El enunciado exige que **Carrito** publique la navegación (agregar, cupón, envío) y no había
carrito ni pedidos: un pago de ms-pago no quedaba unido a nada que comprar, el stock no se
movía y un pago aprobado no tenía consecuencia en ningún otro servicio. La notificación al
cliente —la parte RabbitMQ que el equipo no entregó en la EV2— necesitaba un productor real con
eventos de negocio reales.

## Qué se hizo

```
POST /api/carrito/items ─ ServicioCarrito ─(afterCommit)─┬▶ interacciones-user  AGREGAR_CARRITO
                                                         └▶ RabbitMQ carrito.producto-agregado
POST /api/carrito/cupon ─────────────────(afterCommit)──▶ interacciones-user  APLICAR_CUPON   (detalle.cupon, detalle.productos)
PUT  /api/carrito/envio ─────────────────(afterCommit)──▶ interacciones-user  GESTIONAR_ENVIO (detalle.metodoEnvio, comuna|sucursal)

POST /api/pedidos ─ ServicioPedidos.crearPedido
   1. precio vigente de cada ítem   GET  ms-productos /api/productos/{id}
   2. totales                       CalculadoraTotales (la misma fórmula que el GET del carrito)
   3. reserva todo o nada           POST ms-productos /api/productos/stock/reservas  → 409 con faltantes / 503
   4. Pedido P-<n> en PENDIENTE_PAGO, carrito vaciado; si la transacción se revierte → liberar
   5. afterCommit                   RabbitMQ pedido.creado
   (la tienda llama a ms-pago /api/pagos/iniciar con transactionId = P-<n>, amount = total)

pagos-resultado ──grupo-carrito (3 hilos)──▶ ConsumidorResultadosPago ──▶ ServicioPedidos.procesarResultadoPago
   AUTORIZADO y monto = total → PAGADO;  COMPRA por ítem (Kafka) + pedido.pagado (RabbitMQ)
   RECHAZADO | ANULADO | ERROR → PAGO_RECHAZADO; liberar stock (REST) + pedido.rechazado
   pedido ya resuelto           → se ignora (idempotencia)
pagos-resultado.DLT ──grupo-dlt-carrito──▶ ConsumidorDlt (log ERROR con origen y causa)

SimuladorLogistica @Scheduled 20 s ─▶ avanzarPedido ─▶ RabbitMQ envio.estado-actualizado
```

Piezas, bajo `ms-carrito/src/main/java/com/pedidos360/carrito/`:

| Pieza | Clase |
|---|---|
| Totales, cupones y envío (pura) | `Service/CalculadoraTotales` |
| Máquina de estados (pura) | `Service/MaquinaEstadosPedido`; la aplica `Model/Pedido.cambiarEstado` |
| Carrito / pedidos / resumen admin | `Service/ServicioCarrito`, `Service/ServicioPedidos`, `Service/ServicioResumenAdmin` |
| DTOs de evento (puros) | `Service/EventosCarrito` |
| Kafka | `Config/KafkaConfig`, `Config/KafkaTopicosProperties`, `Component/ProductorInteracciones`, `Component/ConsumidorResultadosPago`, `Component/ConsumidorDlt` |
| RabbitMQ (productor) | `Config/RabbitConfig` (topología + conversor + callbacks), `Component/PublicadorNotificaciones`, `Component/CallbacksConfirmacionRabbit` |
| Publicar sólo tras commit / compensar en rollback | `Component/EjecutorPostCommit` |
| REST a ms-productos con timeouts | `Config/RestClientConfig`, `Component/ClienteProductos` |
| Semilla de cupones, sucursales | `Config/SemillaCupones`, `Model/Sucursal` |

## Decisiones y su porqué

| Decisión | Porqué | Pauta / requisito |
|---|---|---|
| Precio y nombre **siempre** desde ms-productos; el cliente sólo manda `productoId` y `cantidad` (`AgregarItemDTO`) | un precio fijado por el navegador se puede editar. `crearPedido` vuelve a consultar el precio justo antes de comprar: el que estaba guardado hace horas no es el que se cobra | §8 |
| Reservar stock por **REST** dentro de `crearPedido`, no por Kafka | el pedido necesita un sí/no **antes** de existir; un evento no responde. 409 se propaga con `faltantes` y la transacción se revierte: no queda pedido ni se vacía el carrito | Regla de oro §1, §7.2 |
| `EjecutorPostCommit.siSeRevierte` libera el stock si la transacción falla **después** de reservar | la reserva ya ocurrió en otro servicio: sin compensación quedaría stock retenido para un pedido que nunca existió | §8 |
| Eventos Kafka y notificaciones se publican en `afterCommit` (`EjecutorPostCommit.despuesDeConfirmar`) | si el commit falla no se anuncia algo que no ocurrió ("pedido pagado" con el pedido pendiente); y si un broker falla, el negocio ya quedó guardado | Regla de oro §1, Pauta 4 |
| `ProductorInteracciones` y `PublicadorNotificaciones` **nunca lanzan**; `max.block.ms=3000` y `spring.rabbitmq.connection-timeout: 3s` | Kafka y RabbitMQ son dominios de falla independientes: con uno caído el carrito y el pedido siguen. Sin esos topes, `send()` esperaría 60 s por metadata y la petición HTTP quedaría colgada | Regla de oro §1, Pauta 4 |
| Un `COMPRA` **por ítem**; `APLICAR_CUPON` lleva `detalle.productos` | ms-recomendaciones pondera cada producto comprado y reparte el peso del cupón entre lo que había en el carrito | Enunciado (Carrito publica eventos), §2.3 |
| `KafkaConfig` declara a mano productor, consumidor, container factory por DTO y el error handler; `TRUSTED_PACKAGES` = paquete DTO propio, `USE_TYPE_INFO_HEADERS=false`, `read_committed`, `AckMode.RECORD` | cada propiedad queda a la vista con su porqué; el consumidor no depende del paquete Java de ms-pago | Pauta 1, Pauta 4 |
| `grupo-carrito` con `concurrency = 3` = 3 particiones de `pagos-resultado`; key = `idOrden` | reparto real dentro del grupo, y los resultados de un mismo pedido llegan en orden a un solo hilo | Pauta 8, §2.2 |
| `ConsumidorResultadosPago` loguea tópico/partición/offset/key, valida con `Validator` y delega en `ServicioPedidos` | cero negocio en el listener: el servicio se prueba sin broker (`ServicioPedidosTest`) | Pauta 5 |
| `DefaultErrorHandler`: `ExponentialBackOffWithMaxRetries(3)` (1 s, ×2, tope 10 s) y `DeadLetterPublishingRecoverer` a `pagos-resultado.DLT` **misma partición**; no reintentables: `DeserializationException`, validación, `PayloadInvalidoException`, `TransicionInvalidaException` | MySQL o ms-productos caídos se arreglan esperando; un JSON roto o un enum desconocido no. `ErrorHandlingDeserializer` evita el *poison pill* que dejaba al consumidor en bucle | Pauta 6 |
| El productor del DLT usa `DelegatingByTypeSerializer` (`byte[]` crudo, objeto a JSON) | lo que murió al deserializar llega como bytes; publicarlo tal cual conserva el payload original para el análisis | Pauta 6 |
| `ConsumidorDlt` lee el DLT como `String` y loguea `kafka_dlt-original-topic/-partition/-offset`, excepción y payload recortado a 500 | el payload puede ser justamente el JSON inválido; un mensaje enorme no debe inundar el log | Pauta 6, §3.4 |
| La liberación del stock en un rechazo va **dentro** de la transacción (`ServicioPedidos.rechazar`) | si ms-productos no responde se lanza, la BD se revierte y Kafka reintenta el mensaje completo; la liberación es idempotente por `idPedido` (doc 13) | Pauta 6 |
| Idempotencia: un resultado para un pedido que ya no está en `PENDIENTE_PAGO` se ignora con log; un `idOrden` desconocido también (pagos de la consola) | Kafka entrega al menos una vez. Sin el chequeo, `MaquinaEstadosPedido` rechazaría `PAGADO → PAGADO` con `TransicionInvalidaException` y el duplicado terminaría en el DLT como si fuera un error | §8, Pauta 6 |
| AUTORIZADO con `monto` distinto del total → `PAGO_RECHAZADO` y log ERROR "requiere reverso manual" | el monto lo manda la tienda a ms-pago; si no coincide, alguien pagó otra cosa y no se despacha | +5 Webpay (conectado a pedidos) |
| Máquina de estados en una clase pura; `Pedido.cambiarEstado` la invoca | ni el consumidor, ni el scheduler, ni un endpoint futuro pueden inventar saltos (ENVIADO en un retiro, volver de ENTREGADO) | §8 |
| `SimuladorLogistica` avanza un paso cada `ENVIOS_AVANCE_MS` (20 s) y publica `envio.estado-actualizado`; una transacción por pedido | no hay sistema de despacho real; sirve para mostrar notificaciones en vivo. Si un pedido choca con el consumidor (`@Version`), se reintenta en el siguiente tick | §8 |
| Topología RabbitMQ declarada también aquí (`RabbitConfig.topologiaNotificaciones`, `Declarables`) con los argumentos **exactos** de ms-notificaciones | el que arranque primero la crea; redeclarar con iguales argumentos es inocuo y con distintos el broker responde `PRECONDITION_FAILED`. Lo fija `RabbitConfigTest.colasQuorumConLosArgumentosExactosDeMsNotificaciones` | EV2: productores, §9 |
| `publisher-confirm-type: correlated` + `publisher-returns: true` + `template.mandatory: true`; `correlationId` = `idNotificacion`; mensajes `PERSISTENT` con `messageId`, `contentType`, `appId` | el ack del broker dice que el mensaje quedó en la cola quorum; un nack (p. ej. `reject-publish`) o un mensaje sin ruta se loguea en ERROR en vez de perderse en silencio. `messageId` es la clave de deduplicación del consumidor | EV2: productores, §9 |
| Tipo de cupón `ENVIO_GRATIS` además de `PORCENTAJE` y `MONTO` (`Model/TipoCupon`) | §8 sólo nombra dos tipos, pero `ENVIOGRATIS` "descuenta el envío", que no se expresa con ellos sin acoplar el cupón al costo vigente | §8 |
| Cupón que no alcanza el mínimo: 422 al aplicarlo; si después el subtotal baja, queda guardado sin descontar y con `avisoCupon` | si el cliente vuelve a superar el mínimo se reactiva solo. Inactivo e inexistente responden igual (404): no se revela qué cupones existieron | §8 |
| Envío 3 990, gratis desde subtotal **≥** 100 000; retiro en una lista cerrada de 3 sucursales (`Sucursal.buscar` acepta etiqueta, nombre o código, sin tildes) | el cliente no puede inventar una sucursal | §8 |
| `ClienteProductos` con timeouts 2 s / 5 s y **sin** reenviar `X-Usuario-Id` | un ms-productos colgado no debe colgar al carrito ni a los hilos del consumidor; reenviar el header generaría `VER_PRODUCTO` falsos | §8 |

## Verificación

| Qué | Resultado |
|---|---|
| `mvn -B test` en `ms-carrito` | BUILD SUCCESS, **74 tests** (JUnit puro, `@WebMvcTest`, H2, `@EmbeddedKafka`, `RabbitTemplate` y `RestClient` mockeados) |
| `ConsumidorResultadosPagoKafkaTest` (Kafka embebido, 3 particiones) | un resultado válido llega al servicio con todos los campos; tres inválidos con la **misma key** (JSON roto, enum `PAGADO`, `idOrden` vacío) llegan a `pagos-resultado.DLT` en la misma partición, con la causa en los headers, y el válido que viene detrás se procesa igual |
| `ServicioPedidosTest` (12) | creación con precios vigentes, 409 sin pedido ni carrito vaciado, PAGADO con un `COMPRA` por ítem, rechazo/anulación/error liberan stock, resultado repetido y `idOrden` desconocido ignorados, monto distinto no se despacha, ms-productos caído al liberar revierte para que Kafka reintente, logística avanza y notifica |
| `PublicadorNotificacionesTest`, `RabbitConfigTest` | exchange, routing key = tipo, `PERSISTENT`, `messageId`; RabbitMQ caído no rompe la operación; colas quorum con los argumentos exactos del consumidor |
| `CalculadoraTotalesTest` (12), `MaquinaEstadosPedidoTest` (8) | envío en el umbral exacto de 100 000, los tres cupones, mínimo de `GAMER5000`; flujos de envío y retiro, sin saltos ni retrocesos |
| Mutante, en una copia fuera del repo (22-nov): en `ServicioPedidos.procesarResultadoPago` el chequeo `pedido.getEstado() != PENDIENTE_PAGO` se cambió por `false` y se corrió `mvn -B -o test` | BUILD FAILURE, **74 tests, 1 error**: `ServicioPedidosTest.resultadoRepetidoSeIgnoraIdempotencia` con `TransicionInvalidaException: PAGADO → PAGADO`. Confirma las dos redes: sin el chequeo, la máquina de estados impide pagar dos veces, pero el duplicado iría al DLT |
| Prueba de humo `infra/prueba-humo.py` contra el sistema real (22-nov, 22 contenedores sanos), 25/25 dos veces seguidas | agregar al carrito 200; `BIENVENIDA10` con descuento **29 999** (10 % de 299 990); cupón inexistente **404**; envío con total **269 991** (sin costo: subtotal ≥ 100 000); pedido **`P-1` en `PENDIENTE_PAGO`** (201); stock **18 → 17**; pago SIMULADO autorizado y pedido **`PAGADO`** por `pagos-resultado` consumido por `grupo-carrito`; resumen admin con ingresos 269 991 |
| Misma corrida, RabbitMQ | `carrito.producto-agregado`, `pedido.creado` y `pedido.pagado` llegaron a ms-notificaciones |
| Resiliencia: `docker stop ms-notificaciones` y prueba de humo | la compra quedó **PAGADA**; sólo falló el paso de notificaciones (el productor no depende del consumidor) |
| Navegador contra Webpay de integración real | anulación en Transbank → pedido **`PAGO_RECHAZADO`** y stock liberado |
| `kafka-consumer-groups --list` en el clúster real | `grupo-carrito` y `grupo-dlt-carrito` activos |

### No verificado

- **El DLT contra Kafka real**: `pagos-resultado.DLT` existe en el clúster, pero ningún mensaje
  roto se publicó contra los brokers; el camino sólo está probado con `@EmbeddedKafka`.
- **`pedido.rechazado` y `envio.estado-actualizado` en vivo**: la prueba de humo comprueba los
  otros tres tipos. El rechazo se vio en el estado del pedido (navegador), no en la cola ni en
  ms-notificaciones; el avance logístico sólo lo cubre `ServicioPedidosTest`.
- **Un nack o un mensaje devuelto** por el broker: los callbacks están probados con mocks, no
  provocados contra RabbitMQ (por ejemplo, borrando el binding con `?forzar=true`).
- **Concurrencia entre el consumidor y el scheduler** sobre el mismo pedido (`@Version`): está
  razonado en `SimuladorLogistica`, no ejercitado.

## Pendiente

- **`PENDIENTE_PAGO` no vence.** No hay `@Scheduled` que expire pedidos sin resultado: un 429 o
  503 de ms-pago, o un cliente que cierra Webpay sin anular, deja el stock reservado. Lo mismo
  si `rechazar` agota los 3 reintentos (ms-productos caído): el mensaje va al DLT, el pedido
  queda pendiente y **nadie reprocesa el DLT**. Se resuelve en `ServicioPedidos` con un
  vencimiento que pase a `PAGO_RECHAZADO` y libere (ver también doc 10, Pendiente).
- **Ingresos del panel admin que no cuadran** (`ServicioResumenAdmin`): "ingresos" suma el
  total pagado y "ventas por categoría" unidades × precio de lista. `PENDIENTES.md` P5.
- **Sin control de rol** en `/api/admin/**` (`AdminController`): fase 4, `PENDIENTES.md` P4.
- **Notificación perdida con nack**: `CallbacksConfirmacionRabbit` sólo loguea; no hay
  reintento ni *outbox*. Para la demo basta el log; en producción haría falta una tabla de
  salida.

## Trampas para quien siga

- Los argumentos de `notificaciones.cliente` están **escritos a mano** en
  `RabbitConfig.topologiaNotificaciones` (`deliveryLimit(5)`) y son configurables en
  ms-notificaciones (`app.rabbit.topologia.limite-entregas`). Si cambias uno sin el otro, el que
  arranque segundo recibe `PRECONDITION_FAILED` y deja de publicar o consumir.
- `crearPedido` mantiene abierta la transacción de MySQL mientras llama por REST a ms-productos
  (un `GET` por ítem más la reserva). Con un carrito grande y ms-productos lento eso retiene
  conexiones del pool; no metas más llamadas remotas ahí.
- Con `ENVIOS_AVANCE_MS=20000`, a los ~20 s un pedido `PAGADO` ya está en `PREPARANDO`. Para la
  demo en vivo, sube `ENVIOS_AVANCE_MS` en `.env` si necesitas mostrar el estado `PAGADO`.
- `src/test/resources/application.yaml` apaga los listeners (`auto-arranque-listeners: false`)
  y el scheduler; sólo la prueba con `@EmbeddedKafka` los enciende. Un test nuevo que espere
  consumir de Kafka sin encenderlos queda esperando para siempre.
- La rama `NotFound` de `ClienteProductos.liberarStock` nunca se ejecuta: ms-productos responde
  200 `SIN_RESERVA` (doc 13).
- `UsuarioActual` dice que la fase 4 leerá `sub`; el contrato §12 fija `email`. Manda §12.
- El 409 de `POST /api/pedidos` sin `faltantes` no es falta de stock: es un conflicto de reserva
  de ms-productos y trae su `detalle`.
