# Pedidos360 — Arquitectura de la EV3

**Cloud Native I (DSY1107)** · Evaluación 3 · Ismael Oyarzún
Verificado en AWS el 23 de noviembre de 2026 · commit `95ec1af`

---

## 1. El mapa

Cada caja tiene una **etiqueta entre corchetes**. El resto del documento usa esas
etiquetas en vez de repetir nombres largos.

```
                            NAVEGADOR
          ┌──────────────────────┴──────────────────────┐
          │                                            │
    ┌─────▼─────┐                              ┌───────▼───────┐
    │ [F1]      │                              │ [F2]          │
    │ Tienda    │                              │ Consola de    │
    │ :8091     │                              │ operaciones   │
    └─────┬─────┘                              │ :8090         │
          │                                    └───────┬───────┘
          │  HTTPS                                     │
          └──────────────┬─────────────────────────────┘
                         │                        │
                   ┌─────▼──────┐                 │ WebSocket
                   │ [G]        │                 │ (no pasa por [G])
                   │ API        │                 │
                   │ Gateway    │                 │
                   │ /v1 · 22   │                 │
                   │ rutas      │                 │
                   └─────┬──────┘                 │
   ════════════════ EC2 t3.large · 44.208.92.186 ══╪═══════════════════
                         │                        │
   ┌────────┬────────┬───┴────┬─────────┬─────────┼────────┐
   │        │        │        │         │         │        │
┌──▼───┐┌───▼───┐┌───▼───┐┌───▼────┐┌───▼────┐┌───▼───┐┌───▼────┐
│[M1]  ││[M2]   ││[M3]   ││[M4]    ││[M5]    ││[M6]   ││[M7]    │
│Produ-││Carrito││Pago   ││Recomen-││Notifi- ││Kafka  ││Rabbit  │
│ctos  ││       ││       ││daciones││caciones││admin  ││admin   │
│:8086 ││:8087  ││:8083  ││:8084   ││:8088   ││:8085  ││:8089   │
└──┬───┘└───┬───┘└───┬───┘└───┬────┘└───┬────┘└───┬───┘└───┬────┘
   │        │        │        │         │         │        │
   └────────┴────┬───┴────────┴─────┐   │         │        │
                 │                  │   │         │        │
          ┌──────▼──────┐    ┌──────▼───▼───┐     │        │
          │ [K]         │    │ [R]          │◄────┼────────┘
          │ Kafka       │    │ RabbitMQ     │     │
          │ 3 brokers   │◄───┼──────────────┼─────┘
          │ 4 tópicos   │    │ 3 nodos      │
          │ + 4 DLT     │    │ 1 exchange   │
          └──────┬──────┘    └──────────────┘
                 │
      ┌──────────┴──────────┐
      │                     │
┌─────▼─────┐        ┌──────▼──────┐
│ [D1]      │        │ [D2]        │
│ MySQL 8   │        │ DynamoDB    │
│ 1 base    │        │ 3 tablas    │
│ por ms    │        │ (AWS real)  │
└───────────┘        └──────┬──────┘
                            │ stream NEW_IMAGE
                     ┌──────▼──────┐
                     │ [L]         │
                     │ Lambda de   │
                     │ telemetría  │
                     └─────────────┘
```

**21 contenedores** en una sola instancia, 5.404 MB de 7.819 usados.

---

## 2. Los dos frontends

| | [F1] Tienda | [F2] Consola |
|---|---|---|
| Para quién | el cliente que compra | el operador que vigila |
| Repo | `DiVilota/pedidos360-tienda` | `1samadhi/cloud-ev3-front` |
| Stack | React 19 · TypeScript 7 · Vite 8 | igual |
| Pruebas | 57 con Vitest | — |
| Entra por | [G] | [G] + WebSocket directo a [M4] |

Están en **repos separados** del backend. Esa separación era una de las cuatro
observaciones de la EV2.

---

## 3. La puerta de entrada — [G]

API Gateway REST, stage `desarrollo`, **22 rutas declaradas una por una**.

```
https://khca9i6w62.execute-api.us-east-1.amazonaws.com/desarrollo
```

| Ruta | Verbo | Va a |
|---|---|---|
| `/v1/productos` · `/productos/categorias` | GET | [M1] |
| `/v1/carrito` · `/carrito/items` · `/carrito/cupon` | GET POST | [M2] |
| `/v1/carrito/envio` | **PUT** | [M2] |
| `/v1/pedidos` | GET POST | [M2] |
| `/v1/pagos/iniciar` · `/pagos/commit` | POST | [M3] |
| `/v1/alertas` | GET | [M3] |
| `/v1/interacciones` | POST | [M4] |
| `/v1/recomendaciones?userId=` | GET | [M4] |
| `/v1/metricas` | GET | [M4] |
| `/v1/notificaciones` | GET | [M5] |
| `/v1/admin/topicos` · `/admin/cluster` · `/admin/consumer-groups` | GET | [M6] |
| `/v1/rabbit/cluster` · `/rabbit/colas` | GET | [M7] |

**Sin `{proxy+}`**, que era otra de las cuatro observaciones. El precio de no usar
proxy es que todo se declara a mano: los parámetros de consulta y las cabeceras
no viajan solos.

```bash
# Sin esto, ?userId= nunca llega al backend
--patch-operations op=add,\
  path=/requestParameters/integration.request.querystring.userId,\
  value=method.request.querystring.userId
```

Cada ruta lleva además un `OPTIONS` de tipo MOCK para el preflight del navegador.

---

## 4. Los siete microservicios

Java 21 · Spring Boot 3.5.6 · ~16.200 líneas de `main` y 52 clases de prueba.

### [M1] Productos · `:8086` · 1.952 líneas

El catálogo: 20 productos en 8 categorías, con stock. Es quien **reserva stock**
cuando nace un pedido y quien lo libera si el pago falla. Publica cambios de
catálogo a `catalogo-productos` para que [M4] sepa de qué habla.

### [M2] Carrito · `:8087` · 3.509 líneas — *el más grande*

Carrito, cupones, costo de envío, creación del pedido y su máquina de estados:

```
PENDIENTE_PAGO → PAGADO → PREPARANDO → ENVIADO → EN_CAMINO → ENTREGADO
                                     └→ LISTO_RETIRO → RETIRADO
```

También sirve `/api/admin/resumen`, el panel del operador. Consume
`pagos-resultado` para enterarse de si el pago salió bien.

### [M3] Pago · `:8083` · 2.433 líneas

Dos pasarelas detrás de una interfaz:

| | `PasarelaSimulada` | `PasarelaWebpay` |
|---|---|---|
| Estado | funcionando y probada | implementada, sin credenciales reales |
| La tarjeta | la recibe el backend | la captura Transbank en **su** formulario |

**El antifraude** es lo más interesante del servicio. La regla del enunciado
—"misma tarjeta, más de 3 veces en 10 s"— vive en una clase sin Spring, sin base
de datos y sin reloj del sistema, para poder probarla con JUnit plano:

```java
public Decision evaluar(Collection<Instant> instantesPrevios, Instant instanteActual) {
    Instant inicio = inicioVentana(instanteActual);
    long previosEnVentana = instantesPrevios.stream()
            .filter(i -> i.isAfter(inicio) && !i.isAfter(instanteActual))
            .count();
    int total = (int) previosEnVentana + 1;
    return new Decision(total, total > umbral);   // "más de 3", no "3 o más"
}
```

Ese `total > umbral` es deliberado: con umbral 3, **el tercer intento todavía
pasa** (una persona que se equivoca dos veces al tipear) y el cuarto bloquea.
Verificado en AWS: `[200, 200, 200, 429]`.

La tarjeta nunca se guarda. Se identifica por **huella**, un SHA-256 con pepper:

```java
return sha256(digitos + pepper);
```

Y para Webpay, donde el número no existe al iniciar el pago, se usa la cuenta:

> *"Transbank captura la tarjeta en SU formulario y no la entrega antes del pago
> (diseño PCI DSS): en el momento de iniciar no hay número que hashear. Se usa
> entonces la cuenta del cliente como clave; la ventana y el umbral son
> exactamente los mismos, sólo cambia qué se cuenta."*

### [M4] Recomendaciones · `:8084` · 3.303 líneas

Consume `interacciones-user` y arma el "Para ti". Dos consumidores distintos sobre
el mismo tópico, con **grupos separados** para que no se peleen los mensajes:
`grupo-recomendaciones` (calcula) y `grupo-dashboard` (métricas en vivo).

Cada tipo de evento pesa distinto:

```java
case VER_PRODUCTO    -> 1.0;
case APLICAR_CUPON   -> 0.5;
case GESTIONAR_ENVIO -> 0.0;
case AGREGAR_CARRITO -> 3.0;
case COMPRA          -> 5.0;
```

Y el puntaje final combina cuatro afinidades:

```java
double score = PESO_CATEGORIA * afCategoria      // 3.0
             + PESO_SUBCATEGORIA * afSubcategoria // 2.0
             + PESO_MARCA * afMarca               // 1.0
             + PESO_POPULARIDAD * pop;            // 0.5
```

Por eso la recomendación puede explicar **por qué** recomienda:
`"AirPods Pro 2 — Porque compraste WH-1000XM5"`.

Es también el único servicio que escribe en [D2] y el que expone el WebSocket.

### [M5] Notificaciones · `:8088` · 1.495 líneas

Consume de [R] y guarda el historial que el cliente ve. Es el **único servicio sin
Kafka**: usa sólo RabbitMQ.

### [M6] Kafka admin · `:8085` · 2.031 líneas
### [M7] RabbitMQ admin · `:8089` · 1.488 líneas

Los dos "miran" la mensajería y la exponen como API para [F2]: configuración de
tópicos, grupos de consumo con su *lag*, estado del clúster, quórum, colas y
mensajes pendientes. Son el servicio libre que pedía el enunciado.

---

## 5. La mensajería

### [K] Kafka — 3 brokers, 3 Zookeeper

| Tópico | Lo publica | Lo consume |
|---|---|---|
| `interacciones-user` | [M1] [M2] [M4] | [M4] ×2 (recomendaciones y dashboard) |
| `catalogo-productos` | [M1] | [M4] |
| `payment-attempts` | [M3] | [M3] (antifraude) |
| `pagos-resultado` | [M3] | [M2] |

Más **cuatro tópicos `.DLT`**, uno por cada uno.

La **clave** del mensaje no es decorativa: define la partición, y la partición
define el orden. Los eventos de un mismo cliente van con su `clientId` como
clave, así que llegan ordenados entre sí.

### [R] RabbitMQ — 3 nodos con quórum

Un `TopicExchange` y tres colas:

| Cola | Qué recibe |
|---|---|
| `notificaciones.cliente` | lo que el usuario debe ver (3 patrones de binding) |
| `notificaciones.auditoria` | todo, para registro |
| `notificaciones.cliente.dlq` | lo que la cola del cliente rechaza |

```java
Queue cliente = QueueBuilder.durable(p.getColaCliente())
        .deadLetterExchange(p.getExchangeDlx())
        .overflow(QueueBuilder.Overflow.rejectPublish)
```

Ese `rejectPublish` es una decisión: si la cola se llena, **se rechaza publicar**
en vez de descartar en silencio lo más viejo.

### ¿Por qué conviven las dos?

Porque resuelven cosas distintas, y es la pregunta que más probablemente te hagan:

| | [K] Kafka | [R] RabbitMQ |
|---|---|---|
| Modelo | log persistente, se relee | cola, se consume y se va |
| Sirve para | hechos que varios interpretan distinto | avisar a **un** destinatario |
| Acá | una interacción alimenta recomendaciones **y** métricas | una notificación es para un cliente |

---

## 6. El manejo de errores — vale 12% de EP5

Tres piezas en cadena:

```
ErrorHandlingDeserializer → DefaultErrorHandler → DeadLetterPublishingRecoverer
  (no explota al        )   (reintenta o       )   (manda al .DLT con la causa)
  (deserializar basura  )   (descarta          )
```

La política distingue **qué tiene sentido reintentar**:

| Tipo de fallo | Qué hace |
|---|---|
| transitorio (base caída, otro ms no responde) | reintentos exponenciales |
| JSON roto, validación que no pasa | al DLT **de inmediato** |

Reintentar un JSON corrupto mil veces no lo arregla.

Verificado en AWS publicando dos mensajes defectuosos a mano:

```
{esto no es json valido              → causa=JsonParseException    → DLT directo
{"idOrden":null,"estado":"INVENTADO"} → "Fallo 1 procesando..."     → reintentó
                                      → causa=InvalidFormatException → luego al DLT
```

El `ConsumidorDlt` no reprocesa: deja constancia del origen exacto.

> *"El payload se lee como texto porque puede ser justamente el JSON inválido que
> causó el envío al DLT. No se reprocesa: el objetivo es dejar evidencia con el
> origen exacto (tópico/partición/offset) y la causa."*

---

## 7. Persistencia

### [D1] MySQL 8 — una base por microservicio

Cada servicio tiene la suya. Ningún servicio lee la tabla de otro: si [M2]
necesita stock, se lo **pregunta** a [M1] por HTTP.

### [D2] DynamoDB — el real de AWS, no el emulador

| Tabla | Clave | Stream |
|---|---|---|
| `HistorialEventos` | `usuarioId` + `instanteEvento` | — |
| `RecomendacionesUsuario` | `usuarioId` | — |
| `EventosOperaciones-desarrollo` | `tipo` + `ocurridoEn` | NEW_IMAGE → [L] |

La clave de ordenamiento es `timestamp#uuid`: el uuid evita que dos eventos del
mismo milisegundo se pisen.

**El contenedor no lleva credenciales.** Usa el rol de la instancia:

```
arn:aws:sts::471112736485:assumed-role/LabRole/i-0bdac4ec3cb93f53c
```

Eso importa para la demo: las del Learner Lab vencen en una hora y dejarían el
servicio muerto a mitad de la presentación.

---

## 8. Métricas en vivo — WebSocket

`ws://44.208.92.186:8084/ws/metricas`. El servidor **empuja** un snapshot cada 2 s;
el cliente no pregunta nada.

No pasa por [G] a propósito: una API REST de API Gateway no habla WebSocket, eso
requiere una API de tipo WebSocket, que es otro recurso.

El handshake valida el origen con la misma lista que CORS:

```
Origin: http://44.208.92.186:8090   → 101 Switching Protocols
Origin: http://otro-origen.test     → 403
```

---

## 9. Las herramientas, todas juntas

| Capa | Herramienta | Versión |
|---|---|---|
| Lenguaje | Java | 21 |
| Framework | Spring Boot | 3.5.6 |
| Mensajería | Kafka (`confluentinc/cp-kafka`) | 7.5.0 |
| | Zookeeper | 7.5.0 |
| | RabbitMQ | 3.13-management-alpine |
| Datos | MySQL | 8.0 |
| | DynamoDB | servicio real de AWS |
| Frontend | React · TypeScript · Vite | 19.3 · 7.0 · 8.3 |
| Pruebas | JUnit 5 · AssertJ · Mockito · Vitest | — |
| | Testcontainers · EmbeddedKafka | — |
| Infra | Docker Compose | v5.7.1 |
| | buildx | 0.19.3 *(el 0.12 de la AMI no sirve)* |
| AWS | EC2 t3.large · API Gateway REST · DynamoDB · Lambda · Elastic IP | |
| Operación | SSM Run Command (sin abrir SSH) | |
| Observación | kafka-ui `:8080` · RabbitMQ management `:15672` | |

---

## 10. Las cuatro observaciones de la EV2

| Observación | Dónde se resolvió |
|---|---|
| API Gateway para el frontend | [G], 22 rutas |
| No usar proxy en el backend | sin `{proxy+}`, ruta por ruta |
| CORS con la ruta del API del frontend | `CORS_ORIGENES` con el origen real, y validado en el WebSocket |
| Todas las colas implementadas deben funcionar | 4 tópicos + 4 DLT + 3 colas, con prueba de humo 25/25 |

---

## 11. Qué está probado y qué no

| | Estado |
|---|---|
| Prueba de humo (25 comprobaciones) | ✅ verificada en AWS |
| Compra completa por [G] | ✅ carrito → pedido → pago → notificación → recomendación |
| DLT con dos causas distintas | ✅ |
| DynamoDB real | ✅ 3 tablas, datos escritos |
| WebSocket con validación de origen | ✅ |
| Panel admin que concilia | ✅ 81 pruebas de [M2] en verde |
| **Webpay real** | ⚠️ código listo, **sin credenciales de Transbank** |
| **Cognito + JWT** | ⚠️ no implementado |
| **Lambda desde el stream** | ⚠️ tabla con stream lista, Lambda sin desplegar |

---

## 12. Si algo se rompe el viernes

| Síntoma | Causa probable |
|---|---|
| `ExpiredToken` en la consola | credenciales vencidas → `~/.hermes/duoc/aws-cred.sh`. **Los contenedores no se caen por esto** |
| Una ruta nueva da 403 o 500 | el stage tarda hasta un minuto en propagar. `test-invoke-method` prueba sin pasar por el stage |
| `Invalid mapping expression: *` | una cabecera estática debe pasarse por **archivo JSON** (`file://`), no como `clave=valor` |
| El WebSocket da 403 desde el front | `CORS_ORIGENES` del compose está fijado a localhost; lo corrige `docker-compose.override.yml` |
| Un servicio no arranca tras reiniciar | revisar `docker ps -a`: un contenedor viejo puede estar ocupando el puerto |
