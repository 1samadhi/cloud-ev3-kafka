# 13 · El catálogo tiene dueño: ms-productos reserva stock y publica la navegación

Commit `dff55d5` (servicio nuevo, puerto 8086). Contrato: `PLAN-INTEGRACION-EV3.md` §7.
Después lo tocaron `5d55d50` (UID 10001 en el `Dockerfile`) y `4c027b4` (CORS de Actuator
movido a `application.yaml`; ver doc 17). Quien lo llama: ms-carrito (doc 14). Quien lo
consume por Kafka: ms-recomendaciones (doc 11).

## El problema

El enunciado exige que **Productos** publique la navegación ("ver detalle"), y ese servicio no
existía. El catálogo vivía copiado en cada frente: el respaldo en memoria de
ms-recomendaciones, la tienda y `src/lib/catalogo.ts` de la consola, que además es otro catálogo
(ropa y hogar). Desde este commit la fuente única es `SemillaCatalogo` (§7.1). Además nadie era dueño
del stock: un pedido no podía saber si había unidades ni reservarlas.

## Qué se hizo

```
GET  /api/productos[?categoria=&q=&orden=]     ServicioCatalogo.listar → BuscadorCatalogo (sin tildes, puntaje por campo)
GET  /api/productos/categorias                 [{slug, etiqueta, cantidad}]
GET  /api/productos/{id}   (+X-Usuario-Id)  ─▶ ProductorInteracciones ─▶ interacciones-user  (VER_PRODUCTO, key = usuarioId)
POST /api/productos/stock/reservas     ┐
POST /api/productos/stock/liberaciones ├─ ServicioStock ─ CambioCatalogoEvento ─(AFTER_COMMIT)─▶ PublicadorCambiosCatalogo
PUT  /api/productos/{id}/stock         ┘                                                          └▶ ProductorCatalogo ─▶ catalogo-productos (key = productoId)
GET  /api/productos/admin/stock                [{id, nombre, stock, nivel: AGOTADO|BAJO|NORMAL}]
ApplicationReadyEvent ─▶ PublicadorCatalogoInicial ─▶ los 20 snapshots en catalogo-productos
```

Piezas, bajo `ms-productos/src/main/java/com/pedidos360/productos/`:

| Pieza | Clase |
|---|---|
| Semilla de §7.1 (20 productos, 8 categorías), sólo si la tabla está vacía | `Config/SemillaCatalogo` |
| Kafka: dos `ProducerFactory`/`KafkaTemplate` tipados, sin consumidores | `Config/KafkaConfig`, `Config/KafkaTopicosProperties` |
| Productores | `Component/ProductorInteracciones`, `Component/ProductorCatalogo` |
| Publicación tras commit y al arrancar | `Component/PublicadorCambiosCatalogo`, `Component/PublicadorCatalogoInicial` |
| Reservas todo-o-nada, liberación idempotente, reposición | `Service/ServicioStock` |
| Bloqueo `FOR UPDATE` ordenado por id | `Repository/ProductoRepositorio.bloquearPorIds`, `ReservaStockRepositorio.bloquearPorIdPedido` |
| Tabla `reserva_stock` (PK = `idPedido`) | `Model/ReservaStock`, `Model/ItemReservaStock` |
| Identidad (`X-Usuario-Id`, opcional) | `Component/UsuarioActual` |
| Errores `{error, detalle, timestamp}` (+ `faltantes` en el 409) | `Exception/ManejadorGlobalErrores` |

## Decisiones y su porqué

| Decisión | Porqué | Pauta / requisito |
|---|---|---|
| `GET /api/productos/{id}` publica `VER_PRODUCTO` **sólo si viene** `X-Usuario-Id`; anónimo sólo lee (`ServicioCatalogo.obtenerDetalle`) | el evento lleva `usuarioId` como key y sin usuario no hay a quién recomendarle; es lo que alimenta "Porque viste…" en ms-recomendaciones | Enunciado (Productos publica la navegación), Pauta 2 |
| `obtenerDetalle` **sin** `@Transactional` | `send()` puede bloquear hasta `max.block.ms` con Kafka caído; retener una conexión del pool de MySQL mientras tanto no aporta nada | Regla de oro §1 (dominios de falla independientes) |
| `max.block.ms = 3000` (`KafkaConfig.configuracionProductor`), fuera de la lista de §3.1 | el default de Kafka es 60 s: con el clúster caído, ver un producto colgaría la tienda un minuto. Con 3 s se loguea y se sigue | Pauta 1, Pauta 4 |
| Un `ProducerFactory` y un `KafkaTemplate` **por DTO**, `client.id` = `ms-productos-producer-interacciones` / `-catalogo` | el compilador impide publicar un snapshot en `interacciones-user`; dos productores con el mismo `client.id` chocan al registrar sus MBeans JMX (§3.1 decía un solo `<servicio>-producer`) | Pauta 1, Pauta 4 |
| No hay `ConsumerFactory` ni `@KafkaListener` | ms-productos sólo produce (§1). Declarar un consumidor sin listener sería configuración muerta. Por lo mismo no hay DLT ni la prueba de DLT de §3.6: eso se cumple en los servicios que consumen (docs 10, 11, 14) | Pauta 5 y 6 no aplican aquí |
| `ProductorInteracciones` valida el DTO con `jakarta.validation.Validator` **antes** de enviar y nunca lanza | un evento mal formado terminaría en el DLT de ms-recomendaciones; cortarlo donde se generó deja el error junto a su causa. `productoId` es obligatorio salvo `APLICAR_CUPON`/`GESTIONAR_ENVIO` (`EventoInteraccionDTO.isProductoIdValidoParaTipo`) | Pauta 4, Pauta 6, §2.3 |
| Callbacks de `send().whenComplete`: INFO con tópico, partición, offset y key; ERROR con contexto; el fallo síncrono de `send()` se trata igual | §3.2 lo exige y es la evidencia de que el evento salió | Pauta 4 |
| `VER_PRODUCTO` lleva `detalle.nombre` | el dashboard puede mostrar "Galaxy S24" aunque su catálogo en memoria aún no se haya reconstruido desde el compactado | §2.3 |
| `catalogo-productos` con key = `productoId`, snapshot **completo** | la compactación se queda con el último valor por key: la key ES la identidad del producto. Un snapshot parcial o con otra key dejaría versiones viejas vivas para siempre | Pauta 7 (tópico compactado, `infra/kafka/crear-topicos.sh`) |
| Los snapshots de un cambio de stock se publican en `@TransactionalEventListener(AFTER_COMMIT)` | publicar dentro de la transacción dejaría en el tópico un stock que nunca existió cuando la reserva termina en 409 y rollback; ms-recomendaciones lo tomaría como verdad. Lo cubre `PublicacionKafkaIntegracionTest.reservaRechazadaNoPublicaSnapshot` | Pauta 2, Pauta 6 |
| Al arrancar se publica el catálogo completo (`ApplicationReadyEvent`, no `@PostConstruct`) | llega después de `SemillaCatalogo`, así el primer arranque contra una base vacía igual publica los 20; republicar snapshots idénticos es inocuo en un compactado | §7.2 |
| Reserva **todo o nada**: bloquea todas las filas, verifica todos los ítems y recién entonces descuenta; si falta uno, `StockInsuficienteException` → rollback → 409 con `faltantes:[{productoId, disponible}]` | un pedido con stock parcial no sirve: ms-carrito necesita un sí/no en el acto. Un producto inexistente se informa como faltante con `disponible = 0` para que ms-carrito reciba una sola forma de error | §7.2, regla de oro (REST para comandos) |
| Bloqueo pesimista `FOR UPDATE` **ordenado por id** (`ProductoRepositorio.bloquearPorIds`); `@Version` queda como segunda red | serializa reservas concurrentes del mismo producto y el orden fijo evita el deadlock A(101→110) / B(110→101). §7.2 pedía `@Version`; sólo con optimista, dos reservas concurrentes harían fallar a una con 409 en vez de esperar | §7.2 |
| `jakarta.persistence.lock.timeout = 5000`; un `PessimisticLockingFailureException` responde **503** | no colgar el hilo HTTP de ms-carrito; es transitorio, así que 503 (reintentable) y no 409 | §3.5 (códigos) |
| Idempotencia por `idPedido` en `reserva_stock`: reserva repetida → `YA_RESERVADA` sin descontar; liberación repetida → `YA_LIBERADA`; pedido liberado no puede volver a reservar | la liberación llega por dos caminos que pueden repetirse (el consumidor Kafka de ms-carrito la reintenta hasta 3 veces; la compensación de `ServicioPedidos.crearPedido` también libera): ningún reintento puede descontar o devolver dos veces. La reserva existente se busca **después** de tomar los bloqueos, para que dos reintentos simultáneos se serialicen | §7.2, Pauta 6 |
| La liberación devuelve **lo reservado**, no lo que diga el cuerpo; sin reserva responde 200 `SIN_RESERVA` y no toca stock | si se confiara en el cuerpo, cualquiera podría "liberar" unidades que nunca se reservaron | §7.2 |
| Líneas repetidas del mismo producto se suman antes de validar (`ServicioStock.consolidar`, `TreeMap`) | "2 + 3 del mismo producto" se valida como 5; el `TreeMap` da además el orden de bloqueo determinístico | §7.2 |
| `UsuarioActual` es la única clase que lee `X-Usuario-Id` y rechaza con 400 lo que no cumpla `^[A-Za-z0-9._@:+-]{1,64}$` | el valor termina como key de Kafka y en logs: se corta la inyección de saltos de línea. La fase 4 (Cognito) cambia sólo esta clase | §3.5 |
| `ProductoDTO` = snapshot + `categoriaEtiqueta` | la tienda no mantiene su propio diccionario slug → etiqueta | §7.1 |
| `?orden=` desconocido → 400 (`OrdenCatalogo.desde`), no un default silencioso | un typo en la tienda se ve en el acto en vez de ordenar "por relevancia" sin que nadie sepa por qué | §3.5 |

## Verificación

| Qué | Resultado |
|---|---|
| `mvn -B test` en `ms-productos` | BUILD SUCCESS, **46 tests**: `ServicioStockIntegracionTest` 13 (H2), `ControladoresWebMvcTest` 12, `CatalogoIntegracionTest` 6, `BuscadorCatalogoTest` 6, `PublicacionKafkaIntegracionTest` 5 (`@EmbeddedKafka`), `DominioTest` 4 |
| `PublicacionKafkaIntegracionTest` | detalle con usuario publica `VER_PRODUCTO` con key = usuario; sin usuario no publica; reserva/reposición/liberación publican el snapshot con el stock nuevo; reserva rechazada **no** publica; al arrancar salen los 20 con key = `productoId` |
| `ServicioStockIntegracionTest` | todo-o-nada, faltante inexistente con disponible 0, líneas sumadas, reserva y liberación repetidas, cuerpo distinto de lo reservado, liberación sin reserva, y tres pruebas **concurrentes**: no se sobrevende, multi-producto es todo-o-nada, liberaciones simultáneas devuelven el stock una sola vez |
| Mutante, en una copia fuera del repo (22-nov): se borró `throw new StockInsuficienteException(faltantes)` de `ServicioStock.reservar` y se corrió `mvn -B -o test` | BUILD FAILURE, **46 tests, 6 fallas**: 4 en `ServicioStockIntegracionTest` (incluida `reservasConcurrentesNoSobrevenden`), `CatalogoIntegracionTest.flujoDeReservaLiberacionReposicionYAdmin` y `PublicacionKafkaIntegracionTest.reservaRechazadaNoPublicaSnapshot` |
| Prueba de humo `infra/prueba-humo.py` contra el sistema real (22-nov, `docker compose up`, 22 contenedores sanos), 25/25 dos veces seguidas | catálogo con **20 productos y 8 categorías**; tres `GET` de detalle con usuario y después ms-recomendaciones devuelve "AirPods Pro 2 (Porque viste WH-1000XM5)": el `VER_PRODUCTO` llegó por `interacciones-user`; el pedido `P-1` reservó `item_107` y el stock bajó **18 → 17** |
| Navegador contra Webpay de integración real | anulación en Transbank → pedido `PAGO_RECHAZADO` y **stock liberado**: la liberación por `POST /stock/liberaciones` funciona llamada desde el consumidor Kafka de ms-carrito |
| `kafka-topics --describe` en el clúster real (`evidencias/ev3-docker/README.md`) | `catalogo-productos` compactado, `retention.ms=-1`, `min.compaction.lag.ms=60000`, `segment.ms=3600000` |

### No verificado

- **El contenido real de `catalogo-productos`** después de una reserva: lo comprobado en el
  clúster es la configuración del tópico; que el snapshot salga tras el commit sólo está
  probado con `@EmbeddedKafka`.
- **Kafka caído con el servicio arriba**: que `max.block.ms` deje el detalle respondiendo en
  ~3 s no se midió; está razonado en `KafkaConfig`, no probado.
- **Timeout del bloqueo de fila en MySQL** (503): las pruebas concurrentes corren en H2 con
  `LOCK_TIMEOUT=10000`; el camino `PessimisticLockingFailureException` → 503 no se ejercitó.

## Pendiente

- **Sin control de rol** en `PUT /api/productos/{id}/stock` ni `GET /api/productos/admin/stock`
  (`StockController`): queda para Cognito (§12, `PENDIENTES.md` P4).
- **No hay cambio de precio**: §7.2 dice "cada cambio de stock/precio publica el snapshot",
  pero no existe endpoint que modifique precios. Si se agrega, debe pasar por
  `ServicioStock.publicarCambios` (o equivalente) para que el compactado se entere.
- **`reserva_stock` crece sin límite**: no hay limpieza de reservas `LIBERADA` viejas. Para la
  entrega es irrelevante; un `@Scheduled` en `ServicioStock` lo resuelve.

## Trampas para quien siga

- `Component/UsuarioActual` dice en su Javadoc que la fase 4 leerá el claim **`sub`**; el
  contrato §12 (posterior, `5f2dfbc`) fija **`email`**. Manda §12. Lo mismo en ms-carrito y
  ms-notificaciones.
- ms-carrito **no reenvía** `X-Usuario-Id` al consultar precios (`ClienteProductos`). Si alguien
  lo agrega, cada `POST /api/carrito/items` y cada `POST /api/pedidos` generará un
  `VER_PRODUCTO` falso por ítem y ensuciará las recomendaciones.
- La liberación sin reserva responde **200 `SIN_RESERVA`**, no 404. La rama `NotFound` de
  `ClienteProductos.liberarStock` en ms-carrito es código muerto; no lo "arregles" acá
  devolviendo 404.
- `SemillaCatalogo` sólo inserta si `producto` está vacía: cambiar un precio en §7.1 no se ve
  hasta `docker compose down -v` (o borrar la tabla). Reiniciar no basta.
- El 409 trae `faltantes` **y** `detalle`; un 409 **sin** `faltantes` es un conflicto de reserva
  (pedido ya liberado, PK duplicada), no de stock. ms-carrito ya distingue los dos casos.
- `src/test/resources/application.yaml` **reemplaza** al de main (mismo nombre en el
  classpath): una propiedad nueva en `app.*` hay que repetirla ahí o el contexto de prueba no
  arranca (`KafkaTopicosProperties` es `@Validated`).
