# Kit de estudio — EV3 Pedidos360 (Diego)

Para leer en el celular. Está escrito **para tu despliegue**, no el de Ismael.
Fecha: 27-11-2026, 08:30. Todo lo que dice *medido* se midió en tu AWS esta madrugada.

> **Ojo con dos documentos del repo.** `ARQUITECTURA-EV3.md` y `GUIA-DEFENSA-EV3.md`
> los escribió Ismael, desde su lab: hablan de la IP `44.208.92.186`, el gateway
> `khca9i6w62`, 22 rutas `/v1`, "DynamoDB real" y "Cognito no implementado". **Nada
> de eso es tu demo.** Si estudias de ahí vas a defender otro despliegue. Este kit
> los reemplaza.

---

## 0 · Lo primero: encender el lab (10 min antes)

El Learner Lab **se apagó solo** esta madrugada (límite de sesión). Antes de
presentar, en este orden:

1. Abrir AWS Academy → **Start Lab** (esperar el punto verde).
2. Pegar las credenciales nuevas donde las tengas (vencen cada ~4 h).
3. Encender la instancia:
   ```
   aws ec2 start-instances --instance-ids i-0f5f47b9ffdefa327
   ```
4. La IP **no cambia**: `18.211.30.140` es Elastic IP.
5. Los 21 contenedores vuelven solos (`restart: unless-stopped`), pero tardan
   **3 a 5 minutos**. Kafka y RabbitMQ primero, los servicios después.
6. Comprobar, en este orden:
   ```
   curl -s http://18.211.30.140:8091/config.js      # gateway + cognitoClientId
   BASE_HOST=18.211.30.140 python infra/prueba-humo.py   # TODO OK, 25/25
   ```
7. Entrar a la tienda y hacer login con Ana. Si la insignia dice
   **"backend conectado"**, estás listo.

**Lo que se pierde al reiniciar** (y no es un error): DynamoDB Local corre
`-inMemory`, así que las 3 tablas se recrean **vacías**. El historial de eventos
y las recomendaciones guardadas desaparecen; se vuelven a llenar navegando 30
segundos en la tienda. **MySQL no se pierde**: tiene volumen (`mysql_data`), así
que los 20 pedidos, las notificaciones y las alertas de fraude siguen ahí.

**Si algo no levanta**: `docker compose ps` por SSM. Un contenedor en `Exited`
que no sea `crear-topicos` se arregla con `docker compose up -d`.

---

## 1 · El proyecto en 60 segundos (tu apertura)

> "Pedidos360 es una tienda en línea partida en **siete microservicios** Spring
> Boot que se comunican de tres maneras distintas, a propósito: **REST** para las
> órdenes transaccionales, **Kafka** para el registro de hechos que varios
> servicios interpretan distinto, y **RabbitMQ** para las notificaciones que van a
> un destinatario. Corre en **AWS**, en una EC2, con **21 contenedores**: 3 brokers
> Kafka, 3 nodos RabbitMQ, MySQL, DynamoDB, los 7 servicios y los 2 frontends. El
> navegador entra por un **API Gateway** que valida el JWT de **Cognito** antes de
> tocar el backend."

Con eso ya nombraste: microservicios, los 3 canales, alta disponibilidad, cloud,
identidad y gateway. Es casi toda la pauta en un párrafo.

---

## 2 · El mapa

```
          NAVEGADOR
   ┌──────────┴───────────┐
Tienda :8091          Consola :8090
(React, cliente)      (React, operador)
   │                      │
   │  todo por el         │  puertos directos
   │  API Gateway         │  + WebSocket :8084
   ▼                      ▼
API Gateway REST  ───────────────────────────
vih5lem02j /desarrollo · 35 rutas /api/*
authorizer Cognito en 11 rutas
   │
═══╪══════ EC2 t3.large · 18.211.30.140 ══════
   ▼
┌──────────┬──────────┬──────────┬──────────┐
productos  carrito    pago       recomend.
:8086      :8087      :8083      :8084
notificac. kafka-admin rabbit-admin
:8088      :8085       :8089
   │          │           │
   ├─ Kafka (3 brokers, 4 tópicos + 4 DLT)
   ├─ RabbitMQ (3 nodos, 1 exchange, 3 colas)
   ├─ MySQL 8 (4 bases, 16 tablas)
   └─ DynamoDB Local (3 tablas)
```

| | |
|---|---|
| Cuenta AWS | 851725287694 · us-east-1 |
| Instancia | `i-0f5f47b9ffdefa327` · t3.large (7,8 GB) |
| IP | **18.211.30.140** (Elastic IP, fija) |
| Gateway | `https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo` |
| Cognito | pool `us-east-1_Vaxf6Jhxx` · client `3sf3mbsb67lo6njehp8voot8b4` |
| Código | Java 21 · Spring Boot 3.5 · ~16.200 líneas de `main` · 49 clases de prueba |

---

## 3 · Los siete microservicios

| | Puerto | Qué hace | Tamaño |
|---|---|---|---|
| **productos** | 8086 | catálogo (20 productos, 8 categorías), stock, **reserva** y libera stock | 1.952 líneas |
| **carrito** | 8087 | carrito, cupones, envío, pedidos y su máquina de estados, panel admin | 3.509 (el más grande) |
| **pago** | 8083 | 2 pasarelas (simulada y Webpay) + **antifraude** | 2.433 |
| **recomendaciones** | 8084 | "Para ti", métricas en vivo, WebSocket, DynamoDB | 3.303 |
| **notificaciones** | 8088 | consume RabbitMQ, guarda historial, SSE a la campana | 1.495 |
| **kafka-admin** | 8085 | API de administración de Kafka (tópicos, grupos, lag) | 2.031 |
| **rabbit-admin** | 8089 | API de administración de RabbitMQ (colas, exchanges, clúster) | 1.488 |

**Máquina de estados del pedido** (memorízala, la preguntan):
```
PENDIENTE_PAGO → PAGADO → PREPARANDO → ENVIADO → EN_CAMINO → ENTREGADO
                                     └→ LISTO_RETIRO → RETIRADO
```

**Por qué siete y no tres**: el enunciado pide pagos, recomendaciones y uno libre.
Los dos de administración son el libre. Productos, carrito y notificaciones
salieron del dominio: *"el flujo de compra no cerraba sin ellos"*. **No digas**
"para tener más".

---

## 4 · Kafka

### Los cuatro tópicos, y por qué cada uno está configurado distinto

Esto es el **criterio 7** de la pauta ("configuración por flujo, justificada") y
es donde más puntos hay. Común a todos: **RF 3** (una copia por broker) y
**min.insync.replicas 2** (con `acks=all`, se confirma cuando lo tienen 2
réplicas: se tolera 1 broker caído; con 2 caídos el productor recibe error en vez
de aceptar un mensaje que vive en un solo disco). Segmentos de 256 MiB para que
la retención borre de verdad en un disco de demo.

**1. `interacciones-user`** — clave `usuarioId`
- **6 particiones**: es el flujo de mayor volumen (el clickstream). El paralelismo
  máximo de un grupo es el número de particiones; con 6, `grupo-recomendaciones`
  (concurrency 3) toma 2 por hilo y queda margen para crecer a 6 sin reparticionar.
- **retención 7 días**: alcanza para reprocesar la analítica de la semana sin
  guardar comportamiento para siempre.
- **compresión lz4**: eventos JSON chicos y repetitivos; el códec más barato en CPU.

**2. `payment-attempts`** — clave `huellaTarjeta` (SHA-256, nunca el número)
- **3 particiones**: volumen moderado, una por broker reparte el liderazgo.
- La clave es el punto fino: **todos los intentos de una tarjeta caen en la misma
  partición y se leen en orden**, que es exactamente lo que la ventana de 10 s
  necesita. Si la clave fuera aleatoria, la regla no sería confiable.
- **retención 30 días**: auditoría antifraude (un reclamo o contracargo del mes).

**3. `pagos-resultado`** — clave `idOrden`
- **3 particiones**, orden garantizado por orden de compra.
- **retención 90 días**: es el registro de **ventas**, el que más se conserva
  (conciliación y reportes del trimestre).

**4. `catalogo-productos`** — clave `productoId`
- **`cleanup.policy=compact`**, no `delete`. Es el único **compactado** porque es
  **estado, no historia**: cada mensaje es el snapshot completo de un producto y
  sólo importa el último de cada clave (`value=null` = producto borrado).
- **`retention.ms=-1`**: un producto que no cambia en un año sigue siendo catálogo.
- **`min.compaction.lag.ms=60000`**: un snapshot recién escrito no se compacta por
  1 minuto, para que los consumidores en vivo lo alcancen a leer.
- **`segment.ms=1 h`**: el segmento activo nunca se compacta; cerrarlo por tiempo
  (y no por tamaño, que con un catálogo chico no se alcanzaría nunca) hace que la
  compactación ocurra de verdad.
- **`min.cleanable.dirty.ratio=0.1`**: compactar con 10 % de basura, no 50 %.
- Gracias a esto, un consumidor nuevo **reconstruye el catálogo completo** leyendo
  el tópico desde el inicio, sin preguntarle a la base de ms-productos.

**Los 4 `.DLT`**: uno por tópico, con **las mismas particiones** que el original
(el `DeadLetterPublishingRecoverer` publica en la misma partición: con menos,
falla), retención **14 días** y `cleanup.policy=delete`.

### Los grupos de consumo (8)

| Grupo | Lee | Detalle |
|---|---|---|
| `grupo-recomendaciones` | interacciones-user | concurrency 3, recalcula el "Para ti" |
| `grupo-dashboard` | interacciones-user | `latest`, sólo cuenta en memoria para las métricas |
| `grupo-catalogo-recomendaciones` | catalogo-productos | reconstruye el catálogo |
| `grupo-antifraude` | payment-attempts | la ventana de 10 s |
| `grupo-carrito` | pagos-resultado | mueve el pedido a PAGADO |
| `grupo-dlt-ms-pago`, `grupo-dlt-carrito`, `grupo-dlt-recomendaciones` | los `.DLT` | dejan constancia |

**Dos grupos sobre el mismo tópico** es la demostración del modelo de log:
`interacciones-user` lo leen recomendaciones **y** el dashboard, cada uno con su
propio offset, sin robarse mensajes. Detalle que suma: sólo el grupo "dueño"
hace dead-letter; el del dashboard registra y sigue, para que un mensaje malo no
caiga dos veces en el DLT.

### Manejo de errores (vale 12 %)

```
ErrorHandlingDeserializer → DefaultErrorHandler → DeadLetterPublishingRecoverer
 (no explota con basura)    (reintenta o descarta)  (publica al .DLT con la causa)
```

Y **distingue qué vale reintentar**:

| Fallo | Qué hace | Por qué |
|---|---|---|
| base caída, otro servicio no responde | reintentos exponenciales | puede recuperarse |
| JSON roto, validación que falla | al DLT **de inmediato** | reintentarlo mil veces no lo arregla |

Demostración en vivo (deja esta para el final, casi nadie la muestra):
```bash
docker exec -it kafka1 kafka-console-producer \
  --bootstrap-server kafka1:29092 --topic pagos-resultado \
  --property parse.key=true --property key.separator=:
> P-999:esto no es json
```
En el log de ms-carrito aparece el envío al DLT con la causa, el consumidor del
DLT lo registra, y **el siguiente mensaje válido se procesa normal** (eso último
es lo importante: la cola no se bloqueó).

---

## 5 · RabbitMQ

### El clúster
3 nodos `rabbitmq:3.13-management`, descubrimiento `classic_config`, cookie
Erlang compartida, `pause_minority`.

**¿Por qué 3 y no 2?** Una cola *quorum* usa Raft y necesita **mayoría** para
aceptar escrituras. Con 3 nodos la mayoría es 2: se tolera la caída de uno. Con 2
nodos la mayoría también es 2, así que una caída deja la cola **sin líder** — es
peor que no tener clúster.

Las colas son **quorum y durables**: un mensaje confirmado al productor ya está
en la mayoría y sobrevive a la caída de un nodo. Y `continuous_membership_
reconciliation` con `target_group_size=3` las hace crecer a 3 réplicas solas (si
un servicio declara la cola cuando sólo está `rabbitmq1`, quedaría con 1 réplica
para siempre).

### La topología: 1 exchange topic, 3 colas, 5 bindings

```
pedidos360.notificaciones (topic)
   ├── carrito.* ┐
   ├── pedido.*  ├─→ notificaciones.cliente ──(x-dead-letter)─┐
   ├── envio.*   ┘                                            │
   └── #          ─→ notificaciones.auditoria                  │
                                                               ▼
                   pedidos360.notificaciones.dlx (fanout) → notificaciones.cliente.dlq
```

**La respuesta a "sobra una cola"** (te lo dijo el profe; la respuesta es que no):

| Cola | Papel | Sin ella se pierde |
|---|---|---|
| `notificaciones.cliente` | persiste la notificación y la empuja por SSE | el producto |
| `notificaciones.auditoria` | binding `#`: registra **todo**, incluso lo mal formado (recibe el `Message` crudo) | la prueba de que el exchange es **topic** y no direct, y el log de lo que la otra cola rechaza |
| `notificaciones.cliente.dlq` | mensajes muertos con los headers `x-death` | el criterio de DLQ con logs |

Frase para la defensa: **"una entrega, una audita y una recoge lo que murió"**.
Medido: las tres con **1 consumidor** cada una.

### Los detalles que te hacen ver fino

- **`x-dead-letter-strategy=at-least-once` + `x-overflow=reject-publish`**: con el
  default (`at-most-once`) un mensaje muerto se puede perder camino a la DLQ.
  RabbitMQ exige `reject-publish` para at-least-once. Y con la cola llena, el
  productor recibe `nack` en vez de que se descarte en silencio lo más viejo.
- **Política de reintentos propia**, no la de Spring Boot: la de Boot reintenta
  todo, incluido un JSON roto. Acá `PayloadInvalidoException` (extiende
  `AmqpRejectAndDontRequeueException`), `MessageConversionException` y las
  validaciones **no se reintentan**; lo demás, 3 intentos con backoff 1 s ×2
  (tope 10 s) y después al DLX.
- **Tres container factories**: la normal (JSON → DTO), una de **mensajes crudos**
  para auditoría (debe poder registrar lo mal formado) y una para la DLQ. La DLQ
  **no tiene DLX**: es el final del camino, y si falla guardar el mensaje muerto
  lo **reencola** en vez de borrar la evidencia.
- `acknowledge-mode: auto`, `default-requeue-rejected: false` (lo rechazado no
  vuelve a la cola: evita el bucle), `prefetch 10`, `concurrency 1..3`,
  `missing-queues-fatal: false` (el servicio arranca aunque el clúster aún no
  tenga las colas).
- La topología se declara en **dos** servicios (carrito y notificaciones) como
  `Declarables`, con los mismos argumentos fijados por tests: ningún servicio
  depende del orden de arranque, y una cola borrada a mano **reaparece** en la
  siguiente reconexión.

### Demostración de resiliencia (1 minuto, muy efectiva)
```
docker stop ms-notificaciones   → comprar → la compra se completa igual
RabbitMQ :15672 → los mensajes esperando en la cola
docker start ms-notificaciones  → la cola baja a 0 y llega la notificación
```
Eso prueba dos cosas de la pauta a la vez: **persistencia de la cola** y que
**un fallo de mensajería no rompe la operación de negocio**.

---

## 6 · Las bases de datos (lo que te quedaba en duda)

### El mapa

| Servicio | Base | Tablas |
|---|---|---|
| pago | `pagos_db` | `intento_tarjeta`, `bloqueo_tarjeta`, `alerta_fraude` |
| productos | `productos_db` | `producto`, `categoria`, `reserva_stock`, `reserva_stock_item` |
| carrito | `carrito_db` | `carrito`, `item_carrito`, `pedido`, `item_pedido`, `historial_estado_pedido`, `cupon`, `secuencia_pedido` |
| notificaciones | `notificaciones_db` | `notificacion`, `mensaje_fallido` |
| recomendaciones | **DynamoDB** | `HistorialEventos`, `RecomendacionesUsuario`, `EventosOperaciones-desarrollo` |
| kafka-admin, rabbit-admin | **ninguna** | son API sobre los brokers, no guardan estado |

**16 tablas en MySQL, 4 bases, 1 motor.** Conteos medidos anoche: 20 productos,
8 categorías, 20 pedidos, 13 carritos, 100 notificaciones, 1 mensaje fallido,
18 alertas de fraude, 12 bloqueos, 15 intentos.

### Lo que hay que saber decir

**1. Una base por servicio (database-per-service).** Ningún servicio lee la tabla
de otro. Si carrito necesita stock, **se lo pregunta a productos por REST**; si
necesita saber si el pago salió, **lo escucha en `pagos-resultado`**. La prueba es
que cada servicio tiene un único `SPRING_DATASOURCE_URL`, el de su propia base, y
no existe ningún cliente JDBC cruzado.

**2. Un solo contenedor MySQL con cuatro esquemas.** Decilo así, no lo esconds:
*"están separadas **lógicamente** —una base por servicio, sin tablas compartidas—
y comparten **motor** porque es una instancia de lab de 8 GB con 21 contenedores.
En producción serían cuatro instancias RDS, y el código no cambiaría: sólo la URL
de conexión."* Eso es exactamente la respuesta correcta y demuestra que entendés
el patrón **y** su compromiso.

**3. Cómo nacen los esquemas.** No hay script SQL:
- `createDatabaseIfNotExist=true` en la URL JDBC crea la base;
- `ddl-auto: update` (Hibernate) crea y ajusta las tablas al arrancar;
- `SemillaCatalogo` inserta los 20 productos y 8 categorías **sólo si la tabla
  está vacía** (`if (productos.count() == 0)`).

Si preguntan por la debilidad, la honesta es: **no hay migraciones versionadas**
(Flyway/Liquibase). Con `ddl-auto: update` Hibernate agrega columnas pero no
renombra ni borra, y en producción eso no se usa. *"Lo sé, y la razón es que en
una entrega de 3 semanas preferí una sola fuente de verdad —las entidades— a
mantener migraciones a mano."*

**4. Persistencia.** MySQL tiene volumen `mysql_data`: los datos sobreviven a
`docker compose down`, a reiniciar la EC2 y al apagón del lab. **DynamoDB Local
corre `-inMemory` a propósito** (con volumen, la imagen falla por permisos del
usuario `dynamodblocal`), así que sus 3 tablas se recrean vacías en cada
reinicio: `InicializadorTablasDynamo` las crea al arrancar el servicio.

**5. Seguridad del dato.** El 3306 está publicado en el host pero **cerrado en el
grupo de seguridad**: desde internet no se llega a MySQL, sólo a las API. Y la
tarjeta **nunca** se guarda: lo que hay en `intento_tarjeta` es la **huella**
(SHA-256 del número + un *pepper* que viene por variable de entorno) y los
**últimos 4** para mostrar. Si te piden mostrarlo, abrí la tabla: no hay ningún
número de 16 dígitos.

**6. DynamoDB: qué es y por qué.** Es clave-valor, sin esquema, y guarda el
**historial de eventos** del usuario:

| Tabla | Clave de partición | Clave de ordenamiento |
|---|---|---|
| `HistorialEventos` | `usuarioId` | `instanteEvento` (`timestamp#uuid`) |
| `RecomendacionesUsuario` | `usuarioId` | — |
| `EventosOperaciones-desarrollo` | `tipo` | `ocurridoEn` |

El `#uuid` en la clave de ordenamiento evita que dos eventos del mismo
milisegundo se pisen. Encaja con DynamoDB porque la consulta real es siempre
*"dame los eventos de este usuario ordenados por fecha"*: una sola partición, una
sola consulta, sin joins.

> ⚠️ **No digas "DynamoDB real"**. En tu despliegue `DYNAMODB_ENDPOINT` apunta al
> contenedor **DynamoDB Local** y `DYNAMODB_PUBLICAR_OPERACIONES` está en `false`.
> Lo del DynamoDB de AWS y la Lambda es del lab de Ismael (`evidencias/52`). Lo
> correcto: *"el servicio habla el protocolo de DynamoDB contra un contenedor
> local; cambiar a la tabla real es vaciar una variable de entorno, y está
> probado en el otro lab, no en el mío"*.

---

## 7 · Identidad: Cognito + el gateway (tu diferencial)

Esto es lo más fuerte que tenés y es **tuyo**, de ayer. Contalo así:

**Antes**: la identidad era una cabecera. El frontend mandaba `X-Usuario-Id` y los
siete servicios le creían. Cualquiera con `curl` compraba, pagaba o leía los
pedidos de otro:
```bash
curl -H "X-Usuario-Id: admin@pedidos360.cl" http://18.211.30.140:8087/api/admin/pedidos
```

**Ahora**: user pool de Cognito (`us-east-1_Vaxf6Jhxx`) con grupos `cliente` y
`admin`. La tienda pide el token a Cognito, lo manda en `Authorization`, y un
**authorizer de Cognito en el API Gateway** valida el JWT en **11 rutas** y
**reescribe `X-Usuario-Id` con el claim `email`** del token.

La clave de por qué esto es elegante: **los 7 microservicios no cambiaron una
línea**. Siguen leyendo `X-Usuario-Id`; lo que cambió es que ahora esa cabecera la
pone el gateway desde un token firmado, y la del cliente se descarta. La
suplantación dejó de funcionar y el rol de admin sale de `cognito:groups`.

Verificado: **401** sin token, **200** con token, en las 11 operaciones.

**Lo que falta, y conviene que lo digas vos primero**: la **consola** de
operaciones no tiene login y entra por los puertos directos. *"La tienda, que es
donde está el usuario final, quedó cerrada; la consola de operaciones es la
siguiente, y hoy depende de que el grupo de seguridad limite quién llega."*

---

## 8 · El API Gateway

- REST, stage `desarrollo`, **35 rutas `/api/*`**, declaradas una por una,
  **sin `{proxy+}`** (era observación de la EV2: con proxy el gateway es un túnel;
  declarando cada ruta, **el gateway es el contrato**).
- **El precio de no usar proxy**, y mencionalo porque demuestra que lo entendiste:
  nada viaja solo. Cada *query string* y cada cabecera hay que mapearla a mano:
  ```
  integration.request.querystring.userId = method.request.querystring.userId
  ```
  Si no, `?userId=` nunca llega y el servicio responde 400. **Te pasó, y así lo
  arreglaste.** Lo mismo con `X-Usuario-Id`, y ese fallo era **silencioso**: la
  página se veía bien y la telemetría dejaba de recibir.
- **CORS**: no hay comodín `*`. El gateway reenvía el `Origin` y devuelve la
  respuesta CORS que da Spring, que valida contra la lista `CORS_ORIGENES`. Un
  origen ajeno **no recibe cabeceras** (medido anoche). También en las respuestas
  de error.
- **Dos cosas no pasan por el gateway, a propósito**: el **WebSocket** de métricas
  y el **SSE** de notificaciones. Una integración HTTP de API Gateway REST
  *bufferea* la respuesta, así que un stream no la atraviesa; un WebSocket
  necesita una API de tipo WebSocket, que es otro recurso. Van directos.
- El historial `/v1` del principio quedó **superado** por las rutas `/api/*`: el
  frontend construye `<base>/api/<recurso>`, así que con base = gateway y rutas
  `/v1/...` el resultado era `/v1/.../api/...` → 404.

---

## 9 · Pagos y antifraude

**Dos pasarelas detrás de una interfaz**: `PasarelaSimulada` (el backend recibe la
tarjeta) y `PasarelaWebpay` (Transbank captura la tarjeta en **su** formulario).
Evidencia de un pago Webpay **AUTORIZADO** real: pedido `P-17`, Switch OLED,
$329.990, con `pasarela=WEBPAY` en el log de ms-pago, publicado en
`pagos-resultado` y con la notificación `pedido.pagado` recibida
(`evidencias/57`).

**La regla del enunciado**: *"misma tarjeta, más de 3 veces en 10 segundos"*.

```java
int total = (int) previosEnVentana + 1;
return new Decision(total, total > umbral);   // "más de 3", no "3 o más"
```

El detalle que vale contar: es `total > umbral`, no `>=`. Con umbral 3 el **tercer
intento todavía pasa** (alguien que se equivoca dos veces al tipear) y el cuarto
bloquea. En AWS: `[200, 200, 200, 429]`, y el bloqueo dura 30 minutos.

La clase **no usa Spring, ni base de datos, ni el reloj del sistema**: recibe los
instantes y decide. Por eso se prueba con JUnit plano. Eso es diseño, y se nota.

**Con Webpay hay un hueco honesto**: Transbank no entrega el número antes del pago
(diseño PCI DSS), así que al iniciar no hay nada que hashear. Para esa vía la
huella es **la cuenta del cliente**; la ventana y el umbral son los mismos, sólo
cambia qué se cuenta. **Por eso la demo del antifraude se hace con la pasarela
SIMULADA**, que es la única que demuestra la regla *por tarjeta*.

---

## 10 · Recomendaciones y métricas en vivo

Consume `interacciones-user` y arma el "Para ti". Cada evento pesa distinto:
```
ver producto 1.0 · cupón 0.5 · envío 0.0 · agregar al carrito 3.0 · comprar 5.0
```
Y el puntaje combina cuatro afinidades: categoría 3.0, subcategoría 2.0, marca
1.0, popularidad 0.5. De ahí sale que la recomendación **explique el motivo**:
*"AirPods Pro 2 — Porque compraste WH-1000XM5"*. Mostrá eso: que el motivo cambie
de "porque viste" a "porque compraste" es lo que prueba que el evento viajó.

**WebSocket** (`ws://18.211.30.140:8084/ws/metricas`): el servidor **empuja** un
snapshot cada 2 s; el cliente no pregunta nada. El handshake **valida el origen**
con la misma lista que CORS (origen ajeno → 403), que es una defensa que casi
nadie muestra.

---

## 11 · Guion de la demo (≈10 min)

1. `docker compose ps` — 21 contenedores. Kafka UI con los tópicos (**crit. 7**).
2. **Tienda**: login de Ana, navegar productos → la consola muestra el tráfico en
   vivo (**+3**) y "Para ti" cambia con motivo.
3. Carrito, cupón, envío → **campana de notificaciones** (RabbitMQ).
4. **Pago** → pedido PAGADO por `pagos-resultado` (**crit. 2**).
5. **Antifraude**: ráfaga de 4 con la misma tarjeta (pasarela SIMULADA) → `429` y
   alerta.
6. **Fallo controlado**: `docker stop ms-notificaciones`, comprar, mostrar los
   mensajes esperando en `:15672`, `docker start` y verlos llegar.
7. **Mensaje defectuoso al DLT** (**crit. 6**) y el siguiente válido procesándose.
8. **Administración**: cambiar `retention.ms` desde kafka-admin, reset de offsets
   de un grupo detenido (**crit. 3 y 9**); crear y borrar una cola desde
   rabbit-admin (y el **403** al intentar borrar `notificaciones.cliente`: la
   topología del sistema está protegida).
9. Opcional: `docker stop kafka2` y mostrar que el sistema sigue (**RF 3**,
   `min.insync.replicas=2`).

Dejá **6, 7 y 9** para el final: es evidencia que no se ve navegando y es la que
más suma.

---

## 12 · Lo que te falta (decilo vos primero)

| | Estado |
|---|---|
| **Consola de operaciones** | describe el backend anterior, sin login, entra por puertos directos (P1) |
| Puerto en la URL de la tienda | `:8091`; con IP pelada y HTTP no se puede quitar (probado anoche, `docs/trabajo/20-*`) |
| `PENDIENTE_PAGO` nunca vence | un pago abandonado deja el **stock reservado para siempre**; falta un `@Scheduled` que libere reservas viejas |
| El pedido no guarda **qué pasarela** cobró | `codigoAutorizacion` lo dejan las dos; para probar Webpay hay que leer el log de ms-pago |
| Ráfaga simultánea | 4 intentos en el mismo milisegundo pueden dar 4×200 (el consumidor igual bloquea y alerta) |
| Nunca se publica `ERROR` en `pagos-resultado` | todo lo que no es AUTHORIZED sale como `RECHAZADO` |
| `PATCH /notificaciones/{id}/leida` | no verifica el dueño |
| Purgar una cola | no está protegido |
| 12 avisos de lint en la tienda | `react(set-state-in-effect)` |

Decir esto **antes** de que lo encuentren te hace ver más sólido, no más débil. La
fórmula: *"lo conozco, está documentado en `docs/trabajo/`, y así se arregla"*.

---

## 13 · Frases que NO debes decir

| ❌ No digas | ✅ Decí |
|---|---|
| "DynamoDB real de AWS" | "contenedor DynamoDB Local; cambiar a la tabla real es vaciar una variable" |
| "los dos frontends pasan por el gateway" | "la **tienda** entra entera por el gateway; la consola sigue directa" |
| "51 productos" | "20 productos en 8 categorías" |
| "22 rutas `/v1`" | "35 rutas `/api/*`, sin proxy" |
| "Cognito no está implementado" | "Cognito valida el JWT en el borde, en 11 rutas" |
| "la IP es 44.208.92.186" | "18.211.30.140" |
| "el gateway es khca9i6w62" | "vih5lem02j" |
| "las 12 rutas del gateway responden 200" (`evidencias/50`) | "ese documento quedó superado por las rutas `/api/*`, que son las verificadas" |

Las de la izquierda vienen de la documentación de Ismael o de evidencias viejas.
Si las decís y el profe abre el navegador, te quedás sin piso.

---

## 14 · Simulacro: 32 preguntas

### Mensajería (las más probables)

**1. ¿Por qué Kafka y RabbitMQ a la vez? ¿No es redundante?**
Resuelven problemas distintos. Kafka es un **log**: el mensaje queda y varios
consumidores lo leen por separado, cada uno a su ritmo — una interacción la leen
recomendaciones **y** el dashboard, con grupos distintos. RabbitMQ es una **cola**:
la notificación se entrega a un destinatario y se consume. Además son **dominios
de falla independientes**: si RabbitMQ se cae, la analítica sigue.

**2. ¿No podrías hacer todo con Kafka?**
Técnicamente sí. Pero las notificaciones necesitan enrutamiento por patrón
(`pedido.*`, `carrito.*`) y descarte de lo no entregable, que es para lo que
RabbitMQ tiene exchanges y DLQ. Usar la herramienta que calza es más defendible
que forzar una sola.

**3. ¿Por qué 6 particiones en uno y 3 en los otros?**
El paralelismo máximo de un grupo es el número de particiones.
`interacciones-user` es el flujo de mayor volumen y su grupo corre con
concurrency 3: con 6 particiones cada hilo toma 2 y hay margen para llegar a 6
consumidores sin reparticionar. Los otros flujos son de volumen bajo y 3
particiones reparten el liderazgo entre los 3 brokers.

**4. ¿Para qué sirve la clave del mensaje?**
Define la partición, y la partición define el orden. En `payment-attempts` la
clave es la **huella de la tarjeta**: todos los intentos de una tarjeta caen en la
misma partición y se leen en orden, que es lo que la ventana de 10 s necesita. En
`pagos-resultado` es `idOrden`, en `interacciones-user` es `usuarioId`.

**5. ¿Por qué `catalogo-productos` es el único compactado?**
Porque es **estado, no historia**: cada mensaje es el snapshot completo de un
producto y sólo importa el último de cada clave. Con compactación, un consumidor
nuevo reconstruye el catálogo entero leyendo desde el inicio sin preguntarle a la
base de ms-productos. `value=null` significa producto borrado.

**6. ¿Qué es `min.insync.replicas=2`?**
Con `acks=all`, una escritura se confirma recién cuando la tienen 2 réplicas. Con
un broker caído se sigue escribiendo; con dos caídos el productor recibe **error**
en vez de aceptar un mensaje que viviría en un solo disco. Durabilidad sobre
disponibilidad, que para un registro de ventas es lo correcto.

**7. ¿Qué pasa si llega un mensaje corrupto?**
`ErrorHandlingDeserializer` evita que el contenedor explote,
`DefaultErrorHandler` decide si reintenta, y `DeadLetterPublishingRecoverer` lo
publica en el `.DLT` con la causa. Un JSON roto va al DLT **de inmediato**; un
fallo transitorio se reintenta. Lo puedo mostrar en vivo.

**8. ¿Y si el mensaje malo bloquea la partición?**
No, porque va al DLT y el consumidor avanza el offset. Lo demuestro publicando
basura y después un mensaje válido con la misma clave: el segundo se procesa
normal.

**9. ¿Por qué las colas son quorum y no classic?**
Replicación por Raft en los 3 nodos: un mensaje confirmado está en la mayoría y
sobrevive a la caída de un nodo. Y por eso el clúster es de 3 y no de 2: la
mayoría de 2 es 2, así que con 2 nodos una caída deja la cola sin líder.

**10. ¿Qué es la DLQ y cómo llega un mensaje ahí?**
`notificaciones.cliente` tiene `x-dead-letter-exchange` apuntando a un fanout.
Si el payload es inválido, el listener lanza `PayloadInvalidoException` y se
rechaza **sin reencolar** → DLX → `notificaciones.cliente.dlq`. El consumidor de
la DLQ lee los headers `x-death` (cola de origen, motivo, conteo) y los guarda en
la tabla `mensaje_fallido`, que se consulta por `GET /api/notificaciones/fallidas`.

**11. ¿Por qué la cola de auditoría, si duplica todo?**
Porque es la prueba de que el exchange es **topic**: el mismo mensaje llega a dos
colas con patrones distintos (`carrito.*` y `#`) y el productor no sabe cuántas
hay. Y registra también lo mal formado, que la cola del cliente rechaza.

**12. ¿Qué pasa si se cae un nodo de RabbitMQ?**
Las colas quorum siguen aceptando mensajes mientras haya mayoría (2 de 3). El
cliente de management del admin tiene **lista de URL con failover**, así que la
vista del clúster no se cae con el nodo 1.

### Bases de datos

**13. ¿Por qué una base por microservicio?**
Para que el acoplamiento sea por contrato, no por tabla. Ningún servicio lee la
tabla de otro: carrito le **pregunta** el stock a productos por REST y escucha el
resultado del pago en Kafka. Así cada servicio puede cambiar su esquema sin
romper a los demás.

**14. Pero es un solo MySQL…**
Sí: separadas **lógicamente**, cuatro esquemas sin tablas compartidas, mismo
motor porque es una instancia de lab de 8 GB con 21 contenedores. En producción
serían cuatro RDS y el código no cambia, sólo la URL.

**15. ¿Cómo se crean las tablas?**
`createDatabaseIfNotExist=true` crea la base y `ddl-auto: update` de Hibernate
crea y ajusta las tablas al arrancar. El catálogo lo siembra `SemillaCatalogo`
sólo si la tabla está vacía.

**16. ¿No deberías usar migraciones?**
Sí, en producción. No hay Flyway: con `ddl-auto: update` Hibernate agrega
columnas pero no renombra ni borra. Fue una decisión de alcance: una sola fuente
de verdad (las entidades) en vez de mantener migraciones a mano en 3 semanas.

**17. ¿Dónde guardás la tarjeta?**
En ningún lugar. Guardo la **huella**: SHA-256 del número más un *pepper* que
viene por variable de entorno, y los **últimos 4** para mostrar. La respuesta del
pago sólo trae `ultimos4`. Puedo abrir la tabla y mostrar que no hay ningún
número de 16 dígitos.

**18. ¿Y si se pierde la base?**
MySQL tiene volumen Docker, así que sobrevive a reinicios. DynamoDB Local corre
`-inMemory` a propósito y sus tablas se recrean vacías al arrancar: el historial
de eventos se vuelve a llenar navegando. Backups no hay: es un lab.

**19. ¿Por qué DynamoDB para recomendaciones y no MySQL?**
Porque la consulta siempre es "los eventos de este usuario ordenados por fecha":
clave de partición `usuarioId`, clave de ordenamiento el instante. Una sola
partición, una consulta, sin joins, y escala escribiendo mucho. La clave de
ordenamiento lleva `#uuid` para que dos eventos del mismo milisegundo no se pisen.

**20. ¿Cómo mantenés consistencia entre bases sin transacciones distribuidas?**
Con **coreografía**: carrito crea el pedido en `PENDIENTE_PAGO` y pide a productos
**reservar** el stock por REST; ms-pago publica el resultado en `pagos-resultado`
y carrito mueve el pedido a `PAGADO`. No hay 2PC. La compensación es liberar la
reserva — y acá está el defecto que conozco: hoy un pago abandonado deja la
reserva viva porque falta el `@Scheduled` que vence los pendientes.

### Cloud, despliegue y seguridad

**21. ¿Qué hay desplegado en AWS y qué es local?**
Todo el sistema corre en una EC2 t3.large con el **mismo** `docker compose` que en
mi máquina; lo único que cambia son variables de entorno. Fuera de la instancia:
API Gateway, Cognito y la Elastic IP. DynamoDB es el contenedor local.

**22. ¿Por qué no usaste `{proxy+}`?**
Era observación de la EV2. Con proxy el gateway es un túnel que no sabe qué rutas
existen; declarando las 35 rutas una por una, el gateway **es** el contrato. El
costo es que cada query string y cada cabecera hay que mapearla a mano, y si no,
el parámetro no llega — me pasó con `?userId=`.

**23. ¿Dónde está el CORS?**
En el servicio, con lista explícita (`CORS_ORIGENES`), y el gateway reenvía el
`Origin` y devuelve esa respuesta: sin comodín `*`, también en los errores.
Además el handshake del WebSocket valida el origen con la misma lista: un origen
ajeno recibe 403.

**24. ¿Cómo autenticás?**
User pool de Cognito con grupos `cliente` y `admin`. La tienda obtiene el token,
el **authorizer del API Gateway** valida el JWT en 11 rutas y **reescribe
`X-Usuario-Id` con el claim `email`**. Los 7 servicios no cambiaron: la cabecera
que antes era falsificable ahora la pone el gateway desde un token firmado.

**25. ¿Qué pasa si mando `X-Usuario-Id` a mano?**
Ya no funciona: el gateway la sobrescribe. Antes podía leer los pedidos de otro
con `curl`; lo demuestro con 401 sin token y 200 con token.

**26. ¿Y la consola de operaciones?**
No tiene login y entra por los puertos directos. Es lo siguiente en la lista, y
hoy depende de que el grupo de seguridad limite quién llega. No lo escondo.

**27. ¿Por qué se ven los puertos en la URL?**
Las llamadas de la **aplicación** no llevan puerto: la tienda entra entera por el
gateway, se ve en `config.js` o en F12. Lo que muestra `:8091` es la **página**,
el puerto donde la EC2 publica nginx. Lo probé en el 80: el servidor responde 200
y CORS queda bien, pero el navegador no abre `http://IP` sin puerto. Para
quitarlo de verdad hace falta nombre y certificado (CloudFront o dominio).

**28. ¿Cómo operás la instancia?**
Por **SSM Run Command**, sin abrir SSH: no hay puerto 22 expuesto ni llaves que
cuidar.

### Las incómodas

**29. ¿Esto lo hiciste vos?**
El proyecto es en pareja con Ismael Oyarzún. Yo hice la integración de los siete
microservicios, el carrito y los pedidos coreografiados, RabbitMQ con su clúster
y su DLQ, ms-productos, la tienda en React, el despliegue en mi propia cuenta de
AWS y la identidad con Cognito en el API Gateway. Cada pieza tiene su documento
en `docs/trabajo/` con lo verificado y lo no verificado. Si preguntan por algo
que no tocaste: *"esa parte la hizo Ismael; verifiqué que funciona, pero no te
puedo explicar el detalle de la implementación"*.

**30. ¿Qué te faltó?**
La consola alineada y con login, el vencimiento de los pedidos pendientes, las
migraciones de base de datos y el `pasarela` en el pedido. Todo está en
`docs/trabajo/PENDIENTES.md` con prioridad y esfuerzo.

**31. ¿Cómo sabés que funciona y no sólo que arranca?**
Porque "healthy" sólo prueba `/actuator/health`. Hay un script de humo que
recorre las nueve etapas del flujo de negocio y **espera los efectos asíncronos**
(que el evento se consuma, que el stock baje, que el pedido cambie de estado, que
la notificación llegue, que el 4.º intento dé 429): 25 comprobaciones, y lo corro
dos veces seguidas. Más otro script que verifica la identidad y uno que verifica
un pago aprobado.

**32. Si tuvieras una semana más, ¿qué harías?**
Vencimiento de pedidos pendientes para no dejar stock reservado, login en la
consola, migraciones con Flyway y mover el frontend detrás de CloudFront para
tener HTTPS y una URL sin puerto. En ese orden, porque el primero es el único que
puede dejar inconsistente el negocio.

---

## 15 · Chuleta de números

| Dato | Valor |
|---|---|
| Microservicios | 7 (Java 21 · Spring Boot 3.5) |
| Contenedores | 21 + `crear-topicos` que termina en 0 |
| Líneas de `main` / clases de prueba | ~16.200 / 49 |
| Tópicos Kafka | 4 + 4 DLT · RF 3 · min.insync 2 |
| Particiones | 6 (interacciones) · 3 los otros tres |
| Retenciones | 7 d · 30 d · 90 d · compactado · 14 d los DLT |
| Grupos de consumo | 8 |
| RabbitMQ | 3 nodos · 1 exchange topic · 3 colas quorum · 5 bindings |
| MySQL | 4 bases · 16 tablas |
| DynamoDB | 3 tablas (Local, `-inMemory`) |
| Rutas del gateway | 35 `/api/*`, sin proxy · 11 con authorizer |
| Catálogo | 20 productos · 8 categorías |
| Prueba de humo | 25/25 **TODO OK** |
| Antifraude | >3 en 10 s → `[200,200,200,429]`, bloqueo 30 min |
| Pesos del "Para ti" | ver 1.0 · cupón 0.5 · carrito 3.0 · compra 5.0 |
| IP / gateway | 18.211.30.140 · `vih5lem02j` |

---

## 16 · Las cuatro observaciones de la EV2, cerradas

| | Observación | Cómo quedó |
|---|---|---|
| F1 | API Gateway para el frontend | 35 rutas; la **tienda** entra entera, verificado en el navegador |
| F2 | No usar proxy en backend | ningún `proxy_pass`; el navegador llama a la API, no a nginx |
| F3 | CORS con la ruta de la API | lista explícita, sin `*`, también en errores y en el WebSocket |
| F4 | Todas las colas deben funcionar | 3 colas con mensajes reales + 4 DLT de Kafka |

---

## 17 · Regla de oro

Si no sabés algo: **"eso no lo implementé"** o **"esa parte la hizo mi compañero y
no la revisé a fondo"**. Inventar es peor que no saber, porque la pregunta
siguiente te deja sin piso. Y tenés `docs/trabajo/` con 20 documentos que
respaldan lo que sí hiciste, cada uno con su sección **"No verificado"** — mostrar
eso es la mejor respuesta posible a *"¿cómo sabés que funciona?"*.
