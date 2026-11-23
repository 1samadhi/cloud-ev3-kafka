# 10 · El antifraude cuenta la misma tarjeta, con una ventana deslizante real

Commit `f83ace7` (ms-pago). Contrato: `PLAN-INTEGRACION-EV3.md` §4.
Consumidor del resultado: `pagos-resultado` lo lee `grupo-carrito` en ms-carrito.

## El problema

El enunciado pide bloquear cuando **la misma tarjeta** se usa **más de 3 veces en 10 s**.
El código anterior (`Service/ConsumidorComponent.java` y `Model/EstadoPagoCliente.java`,
borrados en este commit) fallaba en las cuatro palabras de la regla (§4.1 del contrato):

1. contaba por `clientId`, no por tarjeta;
2. comparaba sólo contra el intento anterior: era un contador, no una ventana;
3. bloqueaba en el **3.º** intento ("3 o más", no "más de 3");
4. toda la regla vivía dentro del `@KafkaListener`, el `send` no tenía callback, no había
   DLT ni reintentos y `trusted.packages` era `"*"`.

Además, el commit de Webpay no publicaba el resultado: ningún otro servicio se enteraba de
una venta.

El defecto era silencioso porque la demo de antes bloqueaba *algo*: con tres clics rápidos
aparecía el 429 y parecía funcionar. Se encontró leyendo la regla contra el texto del
enunciado, palabra por palabra.

## Qué se hizo

```
POST /api/pagos/iniciar
  └─ ServicioPagos.iniciarPago
       1. huella = HuellaTarjeta.deTarjeta(numero)  | deCuenta(clientId) con WEBPAY
       2. ServicioAntifraude.verificarNoBloqueada   → 429 si hay bloqueo vigente
       3. ServicioAntifraude.anticiparDecision      (lee intento_tarjeta, no escribe)
       4. ProductorIntentosPago.publicarConConfirmacion → payment-attempts (key = huella), 503 si no hay ack en 5 s
       5. si la decisión anticipada bloquea → 429 (el intento YA quedó publicado)
       6. PasarelaPago: WEBPAY → { url, token } · SIMULADA → publica ResultadoPagoDTO → 200 / 402

payment-attempts ──grupo-antifraude (3 hilos)──▶ ConsumidorIntentosPago ──▶ ServicioAntifraude.procesarIntento
                                                   (valida y delega)          VentanaAntifraude.evaluar
                                                                              → intento_tarjeta, bloqueo_tarjeta, alerta_fraude
payment-attempts.DLT ──grupo-dlt-ms-pago──▶ ConsumidorDlt ──▶ ServicioMensajesMuertos (log ERROR)

/api/pagos/commit (Webpay) ──▶ ServicioPagos.confirmarWebpay / registrarAnulacionWebpay ──▶ pagos-resultado (key = idOrden)
```

Piezas, todas bajo `ms-pago/src/main/java/com/kafka/pago/`:

| Pieza | Clase |
|---|---|
| Regla pura, sin Spring ni reloj | `Service/VentanaAntifraude` |
| Huella SHA-256 con pepper | `Service/HuellaTarjeta` |
| Estado antifraude (ventana, bloqueo, alerta, limpieza) | `Service/ServicioAntifraude` |
| Orquestación del pago | `Service/ServicioPagos` |
| Pasarelas | `Service/PasarelaPago` (interfaz; `ServicioPagos` elige por `tipo()` y el resultado `Redireccion`/`Decision` es sellado), `PasarelaWebpay`, `PasarelaSimulada` |
| Kafka | `Config/KafkaConfig`, `Config/KafkaTopicosProperties`, `Component/ProductorIntentosPago`, `ProductorResultadosPago`, `ConsumidorIntentosPago`, `ConsumidorDlt` |
| Tablas | `intento_tarjeta` (índice `huella, instante`; `id_intento` único), `bloqueo_tarjeta`, `alerta_fraude` |

## Decisiones y su porqué

| Decisión | Porqué | Pauta |
|---|---|---|
| La huella es `SHA-256(dígitos + pepper)` en hex; el número no entra al DTO, a la base ni al log (`ServicioPagos.iniciarPago` lo deja en una variable local) | la regla sólo necesita **igualdad** entre tarjetas, nunca saber cuál es; guardar el PAN pondría la base bajo PCI DSS. El pepper (`ANTIFRAUDE_PEPPER`) impide armar una tabla de huellas de números conocidos; `HuellaTarjeta` loguea WARN si queda el valor de desarrollo | Enunciado antifraude |
| Con **WEBPAY** la huella es `"cuenta:" + clientId` (`HuellaTarjeta.deCuenta`) | Transbank captura la tarjeta en **su** formulario y no la entrega antes del pago. Al iniciar no hay número que hashear. La ventana y el umbral son los mismos; cambia qué se cuenta. El prefijo evita que una huella de cuenta coincida con la de un número | Enunciado antifraude, +5 Webpay |
| Existe `PasarelaSimulada` (aprueba todo salvo tarjetas terminadas en `0002`) | es la única forma de ejercer la regla **por tarjeta real**: con Webpay el backend nunca ve el número | Enunciado antifraude |
| Ventana semiabierta `(t − 10 s, t]` que **incluye** el intento actual; bloquea con `total > umbral` (`VentanaAntifraude.evaluar`) | "más de 3" ⇒ el 4.º infringe; un intento ocurrido exactamente 10 s antes ya salió. Ventana y umbral se leen de `app.antifraude.ventana-segundos` / `umbral` | Enunciado antifraude |
| `procesarIntento` usa el `timestamp` **del evento**, no la hora de proceso; el bloqueo dura desde el intento que infringe | si el consumidor se atrasa o reintenta, una ráfaga no se "comprime" ni una serie lenta parece ráfaga; reprocesar un tópico viejo genera bloqueos ya vencidos en vez de castigar hoy | Enunciado antifraude, Pauta 6 |
| `procesarIntento` ignora un `idIntento` ya registrado (`existsByIdIntento` + columna única) | la entrega es *al menos una vez*: tras un rebalanceo el mismo intento puede llegar dos veces e inflaría la ventana | Pauta 6 |
| El HTTP **anticipa** la decisión (`anticiparDecision`) leyendo la misma tabla que mantiene el consumidor, publica el intento igual y recién después responde 429 | sin esto el bloqueo llega un intento tarde: el consumidor decide en forma asíncrona y el 4.º ya habría salido con 200 y cobrado. Se publica igual porque el consumidor necesita ese 4.º para registrar el bloqueo y la alerta. No es una segunda regla: es leer el estado que Kafka ya construyó | Enunciado antifraude, Pauta 5 |
| Publicar el intento espera el ack (`publicarConConfirmacion`, 5 s) y sin ack responde **503** | excepción explícita a §3.2: acá publicar **es** el negocio (sin evidencia en Kafka el antifraude no ve el intento). `max.block.ms=5000` en `KafkaConfig` impide que `send()` quede 60 s esperando metadata con el clúster caído | Pauta 4, contrato §3.2 |
| Key del mensaje = huella (`ProductorIntentosPago`) | misma tarjeta → misma partición → orden garantizado, que la ventana necesita. Si la key no coincide con `huellaTarjeta` el consumidor sólo loguea WARN (cuenta igual con la del cuerpo) | Pauta 2, Pauta 7 |
| `KafkaConfig` declara a mano dos `ProducerFactory` (uno por DTO), el consumidor y la plantilla del DLT; `spring.kafka.*` sólo aporta `bootstrap-servers` | cada propiedad queda a la vista con su motivo (`acks=all`, idempotencia, `lz4`, `ADD_TYPE_INFO_HEADERS=false`, `TRUSTED_PACKAGES=com.kafka.pago.DTO`, `read_committed`, `AckMode.RECORD`) | Pauta 1, Pauta 4 |
| `ConsumidorIntentosPago` sólo loguea tópico/partición/offset/key, valida con `jakarta.validation.Validator` y delega | la regla no sabe que existe Kafka: se prueba sin broker (`ServicioPagosTest`, `VentanaAntifraudeTest`) | Pauta 5 |
| `DefaultErrorHandler` con `ExponentialBackOffWithMaxRetries(3)` (1 s ×2, techo 10 s) y `DeadLetterPublishingRecoverer` a `payment-attempts.DLT` **misma partición**; `DeserializationException`, `PayloadInvalidoException` y validación no se reintentan; `DataAccessException` sí | reintentar un JSON roto no lo arregla; MySQL reiniciándose sí se arregla esperando | Pauta 6 |
| La plantilla del DLT usa `DelegatingByTypeSerializer` (`byte[]` tal cual, objetos a JSON) y **no** recibe `ADD_TYPE_INFO_HEADERS` por propiedades | un mensaje que murió al deserializar llega como bytes; uno inválido, como DTO. El `JsonSerializer` ya va configurado por setter (`noTypeInfo()`): si además recibe la propiedad lanza `IllegalStateException` ("not both"), la publicación al DLT fallaba **siempre** y el registro roto trababa su partición para siempre. Lo destapó la prueba con `@EmbeddedKafka` | Pauta 6 |
| El listener del DLT usa `CommonLoggingErrorHandler` | un DLT del DLT abriría un ciclo sin fin | Pauta 6 |
| `gestionarResultadoCallbackPago` se conserva sobre la huella: un Webpay **AUTORIZADO** borra la ventana y levanta el bloqueo; rechazo o anulación no tocan nada | autorizado prueba que el titular pasó la autenticación del emisor (criterio previo del equipo, doc 02). Con SIMULADA **no** se llama: limpiar en cada aprobación instantánea haría invisible el patrón de un bot probando tarjetas | Enunciado antifraude |
| El commit de Webpay publica `ResultadoPagoDTO` en `pagos-resultado`; la anulación publica `ANULADO` sólo si viene la orden | ms-carrito mueve el pedido y libera el stock por coreografía; sin orden de compra no hay a quién avisarle | Pauta 2 |
| El redirect posterior al commit sale de una lista cerrada por `OrigenPago` (`TIENDA` → `FRONT_URL_TIENDA`, `CONSOLA` → `FRONT_URL`); un valor desconocido cae en `CONSOLA` | redirigir a una URL que mande el cliente sería un *open redirect*. El origen viaja en el query del `returnUrl` porque es lo único que Transbank devuelve tal cual | +5 Webpay |
| `GET /api/alertas` conserva los cinco campos de `AlertaFraudeDTO` y agrega al final `huellaTarjeta` (12 hex) y `ultimos4` | la consola los lee (`AlertaFraude` en su `types.ts`); un cliente viejo ignora los nuevos | — |
| El 429 conserva el `message` anterior y agrega `bloqueadoHasta`, `intentos`, `minutosRestantes`; los minutos se redondean hacia arriba | la consola ya mostraba ese texto; con 20 s restantes debe decir 1 min, no 0 ("habilitado") | — |
| `Clock` es un bean (`AntifraudeConfig`) | una regla temporal que lee `now()` por dentro no se prueba sin dormir el hilo | — |
| `@Scheduled` cada 5 min borra `intento_tarjeta` con más de 60 min | la ventana mira 10 s; guardar más sólo engorda la tabla y vuelve lenta la consulta | — |

## Verificación

| Qué | Resultado |
|---|---|
| `mvn -B test` en `ms-pago` | BUILD SUCCESS, **53 tests** (JUnit puro, `@WebMvcTest`, H2, `@EmbeddedKafka`) |
| `VentanaAntifraudeTest` | 3 en 10 s no bloquea, el 4.º sí, 4 en 11 s no, límite exacto de 10 s excluido, no es un contador del último intento |
| `AntifraudeKafkaIntegracionTest` (Kafka embebido) | `grupo-antifraude` corre con 3 hilos; el 4.º intento de la misma huella bloquea con `intentos=4` y **una** alerta; 4 repartidos en 11 s no bloquean; payload inválido llega a `payment-attempts.DLT` **en la misma partición** y el siguiente válido se procesa igual |
| `AntifraudeKafkaIntegracionTest.rafagaHttpConPasarelaSimulada…` (MockMvc + Kafka embebido) | 3 × 200 `AUTORIZADO`, el 4.º **429** con `intentos=4`, el 5.º 429, alerta con `ultimos4` en `GET /api/alertas`, y el log capturado **no contiene el número** ni con espacios |
| Mutante, en una copia del servicio fuera del repo (22-nov): se borró el `throw antifraude.excepcionPorUmbral(...)` de `ServicioPagos.iniciarPago` y se corrió `mvn -B test` | BUILD FAILURE, **53 tests, 2 fallas**: `AntifraudeKafkaIntegracionTest.rafagaHttpConPasarelaSimulada…` ("expected 429 but was 200") y `ServicioPagosTest.cuartoIntentoSePublicaPeroNoSeCobraYResponde429`. Coincide con lo que registra el cuerpo del commit |
| Prueba de humo `infra/prueba-humo.py` contra el sistema real (22-nov, `docker compose up`, 22 contenedores sanos), dos corridas seguidas de 25/25 | antifraude con SIMULADA y la misma tarjeta, 4 intentos separados por 0,3 s: **`[200, 200, 200, 429]`**; `GET /api/alertas` trae la alerta con los últimos 4 |
| Misma corrida, flujo de compra | pago SIMULADO **AUTORIZADO** sin exponer el número; el pedido pasó a `PAGADO` por `pagos-resultado` |
| Navegador contra Webpay de integración real | compra **ANULADA** en Transbank, vuelta a `/callback?estado=ANULADO`, pedido `PAGO_RECHAZADO` y stock liberado (10): prueba que la anulación publica `ANULADO` y que ms-carrito lo consume |
| `kafka-topics --describe` en el clúster real | `payment-attempts` y `payment-attempts.DLT` con la configuración del contrato (3 particiones, RF 3, min ISR 2, 30 d / 14 d) |

### No verificado

- **Pago Webpay AUTORIZADO** de punta a punta: requiere tipear la tarjeta de prueba en el
  formulario de Transbank. Lo verificado con Webpay real es la anulación; el camino
  autorizado (incluido el borrado de la ventana en `gestionarResultadoCallbackPago`) sólo
  está cubierto por `ServicioPagosTest` con la pasarela mockeada.
- **El DLT contra Kafka real.** `payment-attempts.DLT` existe en el clúster con la
  configuración correcta, pero ningún mensaje roto se publicó contra los brokers reales; el
  camino al DLT sólo está probado con `@EmbeddedKafka`.
- **Ráfagas concurrentes.** La prueba de humo espacia los intentos 0,3 s. Con cuatro
  peticiones simultáneas no se probó qué pasa (ver Trampas).

## Pendiente

- **Un 429 o un 503 deja el pedido en `PENDIENTE_PAGO`.** `ServicioPagos.iniciarPago` no
  publica resultado cuando corta antes de cobrar, y ms-carrito no tiene vencimiento para
  `PENDIENTE_PAGO` (no hay ningún `@Scheduled` que lo expire): el stock queda reservado
  hasta que el cliente reintente con el mismo pedido. Se resuelve publicando `RECHAZADO` en
  ese corte o con un vencimiento en ms-carrito.
- **Error al confirmar Webpay** (`PagosController.confirmarPagoWebpay`, rama `catch`):
  redirige con `estado=ERROR` pero no publica nada; mismo efecto que el punto anterior.
- **El estado `ERROR` del contrato nunca se publica.** `confirmarWebpay` traduce cualquier
  estado distinto de `AUTHORIZED` a `RECHAZADO`. Para ms-carrito da igual (los tres llevan a
  `PAGO_RECHAZADO`), pero el registro de ventas en `pagos-resultado` no distingue "el banco
  rechazó" de "falló algo".
- El código de comercio se lee en un solo lugar (`PasarelaWebpay`, propiedad
  `app.webpay.codigo-comercio`, sobrescribible con `WEBPAY_CODIGO_COMERCIO`); el literal
  `597055555532` queda sólo como valor por defecto de integración (en el `@Value` y en el
  `application.yaml`). Eso atiende el pendiente "Transbank en el código" del `README.md`
  de esta carpeta, que conviene actualizar.

## Trampas para quien siga

- El 429 del 4.º intento depende de que el consumidor **ya haya procesado** los tres
  anteriores: `anticiparDecision` lee `intento_tarjeta`, que escribe el consumidor. Con una
  ráfaga simultánea el 4.º puede pasar con 200; el consumidor igual bloquea y alerta, y el
  5.º recibe 429. No es un defecto de la ventana, es el precio de que la decisión viva en el
  consumidor. La prueba HTTP lo reconoce: espera (`await`) a que estén los 3 intentos en la
  tabla antes de mandar el 4.º.
- El nombre `rafagaHttpConPasarelaSimuladaBloqueaElQuintoConCodigo429…` engaña: la prueba
  exige 429 **en el 4.º** y vuelve a pedirlo en el 5.º, ya con el bloqueo registrado.
- Con Webpay se cuenta por **cliente**, no por tarjeta: cuatro reintentos de la misma
  cuenta en 10 s bloquean aunque sean tarjetas distintas. Para mostrar la regla por tarjeta
  en la defensa, usar `"pasarela": "SIMULADA"`.
- El bloqueo dura 30 min (`ANTIFRAUDE_BLOQUEO_MINUTOS`). Repetir una demo con la misma
  tarjeta da 429 desde el primer intento; la prueba de humo genera una tarjeta nueva por
  corrida por eso.
- Una tarjeta terminada en `0002` siempre se rechaza con 402 en SIMULADA: es la regla
  documentada, no un error.
- No volver a pasar `JsonSerializer.ADD_TYPE_INFO_HEADERS` a la plantilla del DLT: el error
  no se ve en el arranque, sólo cuando llega el primer mensaje roto, y traba la partición.
- `ServicioAntifraude.aHoraLocal` es público a propósito: un método de paquete invocado
  sobre el proxy de Spring no llega al objeto real.
- `ms-pago` y `ms-recomendaciones` ya no comparten `pagos_db`: ms-recomendaciones dejó MySQL
  (doc 11). El pendiente "Base de datos compartida" del `README.md` quedó obsoleto.
