# 12 · ms-kafka-admin administra tópicos y consumer groups, y los tópicos nacen configurados por flujo

Commits: `19a6e48` (ms-kafka-admin), `be64757` (sólo `infra/kafka/crear-topicos.sh`; el
resto de ese commit es ms-rabbit-admin) y el merge `37c2a1d` (lista de tópicos protegidos).
Contrato: `PLAN-INTEGRACION-EV3.md` §2.1 (tabla de tópicos) y §6 (endpoints).
El indicador de salud del clúster (`KafkaClusterHealthIndicator`) es anterior: doc 05.

## El problema

La pauta EP5 pide en los criterios 3 y 9 **crear, listar y modificar** tópicos y
configuraciones, e **inspeccionar y operar** consumer groups; en el 7, retención, limpieza,
particiones y réplicas **ajustadas a cada flujo**. Lo que había:

- ms-kafka-admin sólo **leía**: listar y describir tópicos, listar grupos y su lag, crear y
  borrar tópicos. No había forma de cambiar una configuración, sumar particiones, ver qué
  partición tiene cada miembro de un grupo ni mover sus offsets.
- Los tópicos se creaban todos iguales (3 particiones, 7 días) o por `auto.create.topics`
  con los defaults del broker. Nada que mostrar en el criterio 7.
- Los `get()` sobre `KafkaFuture` no tenían límite: con un broker caído el hilo HTTP quedaba
  colgado. Y `ExecutionException` llamaba a `Thread.interrupt()`, dejando marcado como
  interrumpido un hilo del pool que nadie había interrumpido.
- Los errores se distinguían por el **texto** del mensaje, no por tipo.

Se encontró recorriendo la tabla §0 del contrato criterio por criterio contra los
`@*Mapping` de `KafkaAdminController`: de los criterios 3 y 9 sólo "crear" y "listar"
tenían endpoint.

## Qué se hizo

```
KafkaAdminController (/api)
  GET    /topicos                         listar (excluye "__*")                        ya existía
  GET    /topicos/{n}                     detalle                                        ya existía
  POST   /topicos                         crear: RF ≤ brokers vivos + configs de la lista blanca
  DELETE /topicos/{n}                     400 si es interno o protegido
  GET    /topicos/{n}/configuracion       describeConfigs, marca sobrescritas vs por defecto    nuevo
  PUT    /topicos/{n}/configuracion       incrementalAlterConfigs SET + DELETE                    nuevo
  POST   /topicos/{n}/particiones         createPartitions; 400 si nueva ≤ actual                 nuevo
  GET    /consumer-groups                 listar                                         ya existía
  GET    /consumer-groups/{id}            estado, coordinador, estrategia, miembros y asignación  nuevo
  GET    /consumer-groups/{id}/lag        lag por partición                              ya existía
  POST   /consumer-groups/{id}/offsets/reset  EARLIEST|LATEST|TIMESTAMP|OFFSET; 409 con miembros  nuevo
  DELETE /consumer-groups/{id}            409 con miembros                                         nuevo
  GET    /cluster                                                                        ya existía
  GET    /brokers/{id}/configuracion      describeConfigs(BROKER)                                 nuevo

KafkaAdminService ──▶ ValidadorConfiguracionTopico (lista blanca tipada, pura)
                  └─▶ esperar(KafkaFuture) con app.kafka-admin.timeout-ms → 503; traducir() por TIPO → 400/404/409/503
GlobalExceptionHandler ──▶ { error, detalle, timestamp }

infra/kafka/crear-topicos.sh  ◀── contenedor crear-topicos del compose (cp-kafka:7.5.0, una sola vez,
                                  los microservicios esperan service_completed_successfully)
```

## Decisiones y su porqué

| Decisión | Porqué | Pauta |
|---|---|---|
| **Lista blanca** de 10 claves editables (`ValidadorConfiguracionTopico.LISTA_BLANCA`), idéntica a §6 | Kafka acepta claves que rompen el proyecto sin avisar: `unclean.leader.election.enable=true` pierde mensajes confirmados, `message.timestamp.type` cambia la semántica de las ventanas. Sólo se exponen las perillas que el criterio 7 pide ajustar | Pauta 3, Pauta 7 |
| Valores **tipados**: `min.insync.replicas ≤ RF` del tópico, `cleanup.policy ∈ {delete, compact, compact,delete}`, compresión de un conjunto cerrado, `segment.bytes ≥ 1 MiB`, `min.cleanable.dirty.ratio ∈ [0,1]`; reúne **todos** los errores en un solo 400 | con `min ISR > RF` ninguna escritura con `acks=all` se confirma. Kafka admite segmentos de 14 bytes; segmentos diminutos multiplican archivos abiertos en cada broker. Corregir de a un error por petición es mala experiencia en la consola | Pauta 3, Pauta 6 |
| `PUT` usa `incrementalAlterConfigs` con `establecer` (SET) y `restablecer` (DELETE) en una sola llamada; una clave en ambas listas → 400 | todo o nada en el broker. El `alterConfigs` clásico reemplaza la configuración completa del recurso y borraría los overrides que no se mandan | Pauta 3 |
| Tras el `PUT`, `esperarConfiguracionAplicada` relee hasta ~1 s (10 × 100 ms) | el controlador confirma el cambio, pero `describeConfigs` lo responde un broker desde su caché de metadatos, que puede ir atrasada: sin la espera la respuesta mostraba el valor **viejo** y la consola parecía no haber hecho nada | Pauta 3 |
| "Sobrescrita" depende del nivel (`esSobrescrita`): en un tópico sólo cuenta `DYNAMIC_TOPIC_CONFIG`; en un broker, lo fijado estática o dinámicamente | lo heredado del broker sigue siendo "por defecto" para el tópico; es lo que permite mostrar qué ajustó cada flujo | Pauta 3, Pauta 7 |
| Particiones **sólo aumentan**: 400 si `nueva ≤ actual`, antes de llamar al clúster; la respuesta advierte que `hash(key) % n` cambia y, si el `.DLT` quedó con menos particiones, que hay que subirlo | Kafka no puede reducir sin perder mensajes. Al cambiar el divisor, el orden por key (tarjeta, usuario) sólo vale para lo nuevo. `DeadLetterPublishingRecoverer` publica en la **misma** partición: un DLT más chico falla para las particiones nuevas | Pauta 3, Pauta 6 |
| `POST /topicos` valida `RF ≤ brokers vivos` con `describeCluster` y aplica a sus `configs` la misma lista blanca; `min.insync.replicas` por defecto `min(2, RF)` | con un broker caído, pedir RF 3 falla en el broker con un error menos claro o deja el tópico sub-replicado. Un tópico RF 1 con min ISR 2 no aceptaría ninguna escritura. Los campos sueltos de la consola (`retentionMs`, …) siguen funcionando; si vienen a la vez en `configs`, 400 por ambiguo | Pauta 3 |
| Reset de offsets y borrado de grupo exigen el grupo **vacío** (`exigirGrupoVacio` → 409 listando los `clientId` y el estado) | Kafka sólo lo permite con el grupo vacío; su error (`UnknownMemberId`/`GroupNotEmpty`) no dice quién está conectado. El 409 propio le dice al operador qué consumidores detener | Pauta 9 |
| `OFFSET` fuera de rango se **ajusta** al inicio/fin de la partición (y se informa `ajustado=true`); `TIMESTAMP` sin mensajes posteriores va al final | un offset fuera de rango haría que el consumidor aplique su `auto.offset.reset`, que no es lo que pidió el operador; ir al final con `TIMESTAMP` es lo que hace `kafka-consumer-groups --to-datetime` | Pauta 9 |
| `GET /consumer-groups/{id}` muestra coordinador, estrategia de asignación y la asignación tópico/partición de cada miembro | es la evidencia del criterio 8: se ve a los 3 hilos de `grupo-recomendaciones` repartiéndose las 6 particiones | Pauta 8, Pauta 9 |
| Todo `get()` pasa por `esperar(futuro, operacion)` con `app.kafka-admin.timeout-ms` (10 s); sólo `InterruptedException` re-marca el hilo | un broker caído responde 503 en vez de colgar el hilo HTTP; el bug del `interrupt()` en `ExecutionException` contaminaba hilos del pool | Pauta 6 |
| `traducir()` mapea por **tipo**: `UnknownTopicOrPartition`/`GroupIdNotFound` → 404; `GroupNotEmpty`/`TopicExists`/`RebalanceInProgress` → 409; `InvalidConfiguration`/`InvalidPartitions`/`PolicyViolation` → 400; timeouts y coordinador no disponible → 503 | antes se miraba el texto del mensaje, que cambia entre versiones de Kafka | Pauta 6 |
| Tópicos protegidos en `app.kafka-admin.topicos-protegidos` (los 4 del contrato y sus 4 `.DLT`) en vez de un `Set` en el código | borrar uno en la demo deja a un servicio publicando al vacío; la lista incluye los DLT y se cambia sin recompilar. El merge `37c2a1d` conservó esta forma y el comentario de `master` que explica el porqué (ver Pendiente: la lista arrastra un tópico de más) | Pauta 3 |
| **`crear-topicos.sh` es la fuente única** de la tabla §2.1, ejecutado por un contenedor de un solo uso con `--if-not-exists` | `auto.create.topics` crearía todo con los defaults del broker; idempotente para que `compose up` repetido no falle y los servicios puedan esperar `service_completed_successfully` | Pauta 7 |
| Comunes: RF 3, `min.insync.replicas=2`, `segment.bytes=256 MiB` (salvo catálogo) | 3 brokers: se puede perder uno sin perder datos; con `acks=all` una escritura necesita 2 réplicas, y con 2 brokers caídos el productor recibe error en vez de aceptar un mensaje que vive en un solo disco. La retención y la compactación trabajan por segmento **cerrado**: segmentos chicos vencen antes | Pauta 7 |
| `interacciones-user`: **6** particiones, 7 d, `lz4` | flujo de mayor volumen; 6 particiones dejan a `grupo-recomendaciones` (3 hilos) con 2 por hilo y margen para escalar a 6 sin reparticionar; 7 d alcanzan para reprocesar la analítica de la semana; lz4 abarata JSON chico y repetitivo con el códec más barato en CPU | Pauta 7, Pauta 8 |
| `payment-attempts`: 3 particiones, **30 d** | auditoría antifraude, investigar un contracargo del último mes; la key `huellaTarjeta` lleva cada tarjeta a una partición y en orden, que la ventana de 10 s necesita (doc 10) | Pauta 7 |
| `pagos-resultado`: 3 particiones, **90 d** | es el registro de **ventas**: se conserva más que cualquier otro flujo (conciliación del trimestre) | Pauta 7 |
| `catalogo-productos`: `compact`, `retention.ms=-1`, `min.compaction.lag.ms=60000`, `segment.ms=3600000`, `min.cleanable.dirty.ratio=0.1` | es **estado**, no historia: sólo importa el último snapshot por `productoId`, y un consumidor nuevo reconstruye el catálogo leyendo desde el inicio (doc 11). El segmento activo nunca se compacta: cerrarlo cada hora hace que la compactación ocurra de verdad con un catálogo de 20 productos | Pauta 7 |
| Cuatro `.DLT` con las **mismas** particiones que su origen, 14 d | el recoverer publica en la misma partición; 14 d dan tiempo de revisar, corregir y reprocesar | Pauta 6, Pauta 7 |
| El script termina con `kafka-topics --describe` de los 8 tópicos | deja en el log del contenedor la evidencia de que cada flujo quedó configurado distinto | Pauta 7 |

## Verificación

| Qué | Resultado |
|---|---|
| `mvn -B test` en `ms-kafka-admin` | BUILD SUCCESS, **70 tests**, sin Docker |
| `ValidadorConfiguracionTopicoTest` | la lista blanca es **exactamente** la del contrato; acepta los valores de la tabla §2.1; `min ISR > RF` rechazado; reúne todos los errores en un mensaje |
| `KafkaAdminServiceTest` (`AdminClient` mockeado) | particiones iguales o menores se rechazan **sin** llamar a `createPartitions`; mayor llama `increaseTo` y advierte si el DLT queda corto; reset con miembros → 409 sin tocar offsets; `OFFSET` fuera de rango se ajusta; clúster que no responde → 503; los DLT están protegidos |
| `KafkaAdminControllerTest` (`@WebMvcTest`, servicio mockeado) | 400 (fuera de lista blanca, JSON roto, reducir particiones, estrategia desconocida), 404 (tópico o grupo inexistente), 409 (reset o borrado con miembros), 503 (clúster caído) y 405 en vez de 500 ante un verbo equivocado |
| `KafkaAdminIntegracionTest` (`@EmbeddedKafka`, 1 broker) | la configuración y las particiones **cambian de verdad en el broker** (`retention.ms`, `compact,delete`, `segment.bytes`, 2→3 particiones); un grupo con un consumidor vivo aparece `Stable` con 2 asignaciones y reset/borrado dan 409; al cerrar el consumidor queda `Empty`, `EARLIEST` deja lag 10 y se borra; broker inexistente → 404 |
| Mutante, en una copia del servicio fuera del repo (22-nov): se quitó `exigirGrupoVacio(grupo, "resetear sus offsets")` de `resetearOffsets` | BUILD FAILURE, **70 tests, 2 fallas**: `KafkaAdminServiceTest.resetConMiembrosActivosEs409YNoTocaOffsets` y `KafkaAdminIntegracionTest.consumerGroupConMiembros…` (el broker real rechazó igual, pero sin el detalle "miembro(s) activo(s)": la verificación propia es la que hace legible el 409) |
| Prueba de humo `infra/prueba-humo.py` contra el sistema real (22-nov, 22 contenedores sanos), dos corridas de 25/25 | `GET /api/topicos/interacciones-user/configuracion` → 200; `GET /api/consumer-groups/grupo-recomendaciones` → 200 con **3 miembros** |
| `kafka-topics --describe` en el clúster real | los 4 tópicos y sus 4 DLT con la configuración del contrato; 8 consumer groups activos |

### No verificado

- **`PUT` de configuración, aumento de particiones, reset de offsets y borrado de grupo
  contra el clúster real de 3 brokers.** Están probados contra `@EmbeddedKafka` de **un**
  broker; la prueba de humo sólo ejerce las lecturas. En particular, la regla
  `RF ≤ brokers vivos` y `min.insync.replicas ≤ RF` con RF 3 no se ejercieron contra
  brokers reales.
- `crear-topicos.sh` corriendo **dos veces** sobre tópicos ya existentes: el
  `--if-not-exists` lo garantiza por diseño de `kafka-topics`, pero el `up` del 22-nov
  partió de un clúster vacío.
- Que la compactación de `catalogo-productos` haya ocurrido de verdad: con
  `segment.ms` de 1 h hacía falta dejar el sistema arriba más de una hora, y no se miró.

## Pendiente

- ~~La lista de tópicos protegidos arrastra `telemtria-camion`.~~ **Corregido** en el commit
  siguiente al merge: el cuerpo de `37c2a1d` afirmaba que la lista quedó sin el tópico de
  camiones, pero `19a6e48` lo había dejado como noveno elemento en `application.yaml` y en el
  valor por defecto de `KafkaAdminProperties.topicosProtegidos`. Se quitó de los dos lugares,
  que es lo que pretendía `master` (`7486d4a`).
- **Los scripts de `despliegue/` (traídos por `37c2a1d`) contradicen `crear-topicos.sh`.**
  `despliegue/03-topicos-ev3.sh` crea los tópicos con 3 particiones y 7 días para todos: la
  arquitectura anterior de 2 tópicos. Desplegar en AWS con ese script pierde todo el
  criterio 7. Lo más simple es que la fase 03 ejecute `infra/kafka/crear-topicos.sh` con
  `BOOTSTRAP` apuntando al clúster del lab. Está asignado como **P2** en
  [`PENDIENTES.md`](PENDIENTES.md).
- El `README.md` de esta carpeta dice que ms-kafka-admin "protege `payment-attempts` e
  `interacciones-user`"; ahora son los 8 tópicos del contrato. Hay que actualizarlo.
- No hay endpoint para **sumar particiones al DLT junto con su origen**: la API sólo
  advierte. Si alguien sube `interacciones-user` a 9, debe subir a mano
  `interacciones-user.DLT`.

## Trampas para quien siga

- `--if-not-exists` **no modifica** un tópico existente. Cambiar un valor en
  `crear-topicos.sh` no tiene efecto sobre un clúster con datos: hay que usar
  `PUT /api/topicos/{n}/configuracion` o `kafka-configs --alter`.
- El script usa `kafka1:29092` (listener interno de la red del compose). Desde el host es
  `BOOTSTRAP=localhost:9092`; dentro de la red el `9092` no existe.
- Borrar un tópico protegido devuelve **400**, no 403 ni 409: es una
  `SolicitudInvalidaException`. La consola debe mostrar el `detalle`.
- Para resetear offsets de un grupo del sistema (p. ej. `grupo-recomendaciones`) hay que
  **detener el servicio** dueño del grupo: mientras corra, el 409 es correcto.
- Aumentar particiones de `payment-attempts` en plena actividad puede dejar los intentos
  de una tarjeta repartidos en dos particiones (`hash(key) % n` cambia), procesados por
  hilos distintos y fuera de orden. `ServicioAntifraude.procesarIntento` sólo cuenta los
  intentos **anteriores** al evaluado: si el 4.º se procesa antes que el 3.º, ninguno de
  los dos ve 4 en la ventana y el bloqueo no ocurre. No hacerlo en medio de la demo.
- El test de integración corre con **un** broker: por eso `min.insync.replicas` por defecto
  baja a 1 ahí. No copiar ese valor al clúster real.
- El nombre del tópico de la arquitectura anterior es `telemtria-camion` (sin la segunda
  "e"). Cualquier grep por "telemetria" no lo encuentra.
