# PLAN DE CONSTRUCCIÓN — Evaluación 3 (DSY1107)

> Este documento es el **contrato de construcción**. Un subagente debe poder tomar cualquier
> sección y construirla sin preguntar nada. Si algo no está acá, está mal el plan — avisar,
> no improvisar.
>
> Leer antes: `../_CONTEXTO-EXP3.md` (rúbricas, erratas) y `../_ESTILO-DEL-DOCENTE.md`
> (convenciones de código obligatorias).

## Estado de la infraestructura — YA HECHO Y VERIFICADO (02-nov)

`infra/docker-compose.yml` levantado y sano:

| Servicio | Puerto host | Estado |
|---|---|---|
| zookeeper1/2/3 | 2181 / 2182 / 2183 | healthy · quórum formado (1 leader, 2 followers) |
| kafka1/2/3 | 9092 / 9093 / 9094 | healthy |
| kafka-ui | 8080 | arriba |

Tópicos creados, verificados con `--describe`:

```
pagos.intentos       3 particiones · 3 réplicas · ISR 3/3
navegacion.eventos   3 particiones · 3 réplicas · ISR 3/3
retention.ms=604800000 · cleanup.policy=delete · min.insync.replicas=2
```

Bootstrap para los microservicios: `localhost:9092,localhost:9093,localhost:9094`
(los tres, para que el cliente sobreviva a la caída de un broker).

---

## Arquitectura de los tres microservicios

```
                    ┌──────────────────────────────────────┐
  POST /api/pagos   │  ms-pago               :8083         │
 ────────────────►  │  - valida payload                    │
                    │  - consulta ventana antifraude       │
                    │  - bloquea o procesa                 │
                    └──────────┬───────────────────────────┘
                               │ publica  key = hashTarjeta
                               ▼
                    ╔══════════════════════╗
                    ║  pagos.intentos      ║  3 part · 3 repl
                    ╚══════════┬═══════════╝
                               │ group: grupo-antifraude
                               ▼
                    ┌──────────────────────────────────────┐
                    │  ms-pago (consumer interno)          │
                    │  cuenta intentos en ventana 10 s     │
                    └──────────────────────────────────────┘

  POST /api/eventos ┌──────────────────────────────────────┐
 ────────────────►  │  ms-analytics          :8084         │
                    │  publica evento de navegación        │
                    └──────────┬───────────────────────────┘
                               │ publica  key = usuarioId
                               ▼
                    ╔══════════════════════╗
                    ║  navegacion.eventos  ║  3 part · 3 repl
                    ╚══════════┬═══════════╝
                               │ group: grupo-analytics
                               ▼
                    ┌──────────────────────────────────────┐
                    │  ms-analytics (consumer)             │
                    │  perfil de navegación + recomendar   │
                    │  GET /api/recomendaciones/{usuario}  │
                    └──────────────────────────────────────┘

                    ┌──────────────────────────────────────┐
                    │  ms-kafka-admin        :8085         │
                    │  AdminClient: crear/listar tópicos,  │
                    │  describir consumer groups y LAG     │
                    └──────────────────────────────────────┘
```

**Por qué la key es `hashTarjeta` y `usuarioId`:** Kafka garantiza orden sólo *dentro* de una
partición. Usando la tarjeta como key, todos los intentos de esa tarjeta caen en la misma
partición y llegan ordenados — que es exactamente lo que la ventana de 10 segundos necesita
para contar bien. Lo mismo con el usuario y su clickstream.

---

## 1. `ms-pago` — puerto 8083

### DTO: `IntentoPagoDTO`
```
String  idTransaccion     UUID generado si no viene
String  tarjetaHash       SHA-256 de los 16 dígitos, NUNCA el número en claro
String  tarjetaUltimos4   para mostrar en UI y logs
Long    monto             > 0
String  moneda            "CLP" por defecto
String  idOrden
Long    timestamp         epoch millis
String  estado            APROBADO | RECHAZADO | BLOQUEADO_ANTIFRAUDE
```

### Reglas obligatorias

**Antifraude (lo exige el enunciado, textual):** detectar si la misma tarjeta se repite
**más de 3 veces en un intervalo de 10 segundos** → bloquear preventivamente y registrar alerta.

Implementación: por `tarjetaHash`, mantener una cola de timestamps. Al llegar un intento nuevo,
descartar los que quedaron fuera de la ventana de 10 s y contar los que quedan. **Si quedan 3 o
más previos (o sea, este es el 4.º), se bloquea** — "más de 3 veces" significa que el cuarto ya
infringe.

El bloqueo **debe persistir**: una vez bloqueada, la tarjeta queda bloqueada aunque baje el
ritmo (si no, con esperar 11 segundos el atacante sigue). Usar un `Set` de tarjetas bloqueadas
con TTL configurable (default 5 min).

**Respuestas HTTP:**
- intento normal → `200` con el DTO y `estado=APROBADO`
- bloqueado por ventana → `429 Too Many Requests` con el motivo y el conteo
- tarjeta ya bloqueada → `403 Forbidden`
- payload inválido → `400` con el detalle del campo

Los códigos distintos importan: la evidencia con Postman debe mostrar 200, 200, 200, **429**.

### Validación de entrada (criterio de rúbrica)
`@Valid` con `@NotBlank`, `@Positive` en monto, y rechazo explícito de tarjetaHash vacío.

### Webpay
Dejar una interfaz `PasarelaPago` con implementación `PasarelaSimulada` por defecto.
La integración real de Webpay (+5) se agrega después como segunda implementación, sin tocar
el resto. **No implementar Webpay ahora.**

---

## 2. `ms-analytics-recommendations` — puerto 8084

### DTO: `EventoNavegacionDTO`
```
String  idEvento
String  usuarioId
String  tipoEvento      VER_PRODUCTO | APLICAR_CUPON | GESTIONAR_ENVIO | AGREGAR_CARRITO
String  productoId
String  categoria
Long    timestamp
```

Los cuatro tipos salen del enunciado: *"al ver el detalle de un producto, aplicar un cupón o
gestionar envíos"*.

### Lógica de recomendación
Mantener por usuario las últimas N categorías/productos vistos (default 20). Recomendar
productos de las categorías más frecuentes, excluyendo los ya vistos. Es suficiente y es
defendible: no se pide un modelo de ML.

### Endpoints
- `POST /api/eventos` — publica un evento al tópico
- `POST /api/eventos/simular?cantidad=50` — simulador para la demo
- `GET /api/recomendaciones/{usuarioId}` — devuelve las recomendaciones calculadas
- `GET /api/metricas` — **3 métricas para el dashboard**: productos más vistos, eventos por
  minuto, usuarios activos. (Las 3 métricas son requisito del +3.)

### Persistencia
Primera versión **en memoria**. DynamoDB (+2) se agrega después detrás de una interfaz
`RepositorioEventos`, para no reescribir nada.

---

## 3. `ms-kafka-admin` — puerto 8085

Este servicio **no está en el enunciado pero sí en la rúbrica** (12% de EP5 entre dos criterios)
y probablemente es el "Microservicio Libre" del título.

Usa `AdminClient` del cliente Kafka. Endpoints:

- `GET  /api/topicos` — lista con particiones, réplicas e ISR
- `GET  /api/topicos/{nombre}` — detalle y configuración
- `POST /api/topicos` — crea con particiones, réplicas y configs
- `DELETE /api/topicos/{nombre}`
- `GET  /api/consumer-groups` — lista
- `GET  /api/consumer-groups/{id}/lag` — **offsets y lag por partición**

El último es clave: la rúbrica de EP6 da **15%** a "supervisión mostrando offsets, lag y
distribución del consumo". Tener un endpoint propio que lo exponga es mejor demo que el UI.

**Validación obligatoria:** rechazar nombres vacíos, particiones ≤ 0, replication factor
mayor que el número de brokers. Es criterio explícito en la rúbrica de la EP3 y se repite.

---

## Reglas transversales (criterios de rúbrica, no opcionales)

1. **Separar lógica de infraestructura.** El `@KafkaListener` sólo deserializa y delega a un
   `Service`. Nada de reglas de negocio dentro del listener. *(14% en EP5)*
2. **Manejo de errores:** payload inválido → log de nivel WARN + descartar, sin tumbar el
   consumidor. Errores transitorios → reintento. Nunca dejar que una excepción mate el listener.
   *(12% en EP5)*
3. **Consumer groups distintos por dominio**: `grupo-antifraude`, `grupo-analytics`. *(12%)*
4. **DTO tipados y serializers explícitos** en la config, no por defecto. *(14%)*
5. **Config centralizada** en `application.yaml` + clase `@Configuration`. Ningún nombre de
   tópico hardcodeado en medio de la lógica: constantes o propiedades.
6. **Logging con partición, offset y key** en cada consumo — es la evidencia que el docente
   busca, y su propio código lo hace así.

## Convenciones de código (de `_ESTILO-DEL-DOCENTE.md`)

- Paquetes con mayúscula: `Config`, `Controller`, `Model`, `Repository`, `Service`, `DTO`
- `groupId` = `com.pedidos360`, artifactId = nombre del servicio
- Spring Boot 4.1.2 · Java 21 · `application.yaml`
- Dockerfile multi-stage con usuario `spring` no-root (copiar el del estilo)
- Nombres en español
- Monorepo: una carpeta por servicio, cada una con su `pom.xml` y `Dockerfile`

## Estructura esperada

```
evaluacion3/
├── infra/                      ✅ HECHO
│   ├── docker-compose.yml
│   └── .env
├── ms-pago/                    pom.xml  Dockerfile  src/
├── ms-analytics/               pom.xml  Dockerfile  src/
├── ms-kafka-admin/             pom.xml  Dockerfile  src/
├── docker-compose.yml          los 3 servicios, para levantarlos junto a infra
└── evidencias/                 capturas de Postman, Kafka UI, lag
```

## Orden de construcción

1. `ms-kafka-admin` — el más simple, y da la herramienta de monitoreo para probar el resto
2. `ms-pago` — el que tiene la regla de negocio más exigida
3. `ms-analytics` — el que habilita los extras (DynamoDB, dashboard)

## Fuera de alcance por ahora

Webpay, DynamoDB, Lambda, API Gateway WebSocket y el dashboard. Se agregan **después**, detrás
de las interfaces que deja este plan, cuando el núcleo esté probado.
