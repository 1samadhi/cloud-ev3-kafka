# Changelog

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y
[versionado semántico](https://semver.org/lang/es/). La versión mayor sigue a la evaluación
del ramo: **3.x = Evaluación 3**.

## Compatibilidad entre repositorios

El sistema son tres repos que se despliegan juntos. Estas versiones están probadas entre sí:

| cloud-ev3-kafka | pedidos360-tienda | cloud-ev3-front (consola) | Estado |
|---|---|---|---|
| `v3.0.0-rc.1` | `v3.0.0-rc.1` | `v2.0.0` | probado con `docker compose up` (doc 17). La consola funciona pero describe parte del sistema anterior: ver P1 en `docs/trabajo/PENDIENTES.md` |

## [Sin publicar]

Lo que se haga desde `PENDIENTES.md` va acá hasta la entrega, que se etiqueta `v3.0.0`.

## [3.0.0-rc.1] — 2026-11-22

Integración completa de la EV3: siete microservicios sobre Kafka, RabbitMQ y REST, levantados
y verificados juntos por primera vez.

### Agregado

- `PLAN-INTEGRACION-EV3.md`: contrato de tópicos, grupos, DTOs, colas, puertos y API, mapeado a
  la pauta EP5 y al enunciado (doc 09).
- **ms-productos** (8086): catálogo de 20 productos en 8 categorías, stock con reservas todo o
  nada, evento `VER_PRODUCTO` y snapshots al tópico compactado `catalogo-productos` (doc 13).
- **ms-carrito** (8087): carrito, cupones, envío o retiro y pedidos coreografiados por
  `pagos-resultado`; publica la navegación en Kafka y las notificaciones en RabbitMQ (doc 14).
- **RabbitMQ**: clúster de 3 nodos, colas quorum con DLX/DLQ; **ms-notificaciones** (8088) con
  reintentos, DLQ registrada en log y en base, y SSE; **ms-rabbit-admin** (8089) para colas,
  exchanges y bindings (doc 15).
- ms-kafka-admin: modificar configuración de tópicos, aumentar particiones, detalle, reset de
  offsets y borrado de consumer groups, configuración de brokers (doc 12).
- `infra/kafka/crear-topicos.sh`: 4 tópicos y 4 DLT con configuración distinta por flujo.
- ms-recomendaciones: DynamoDB, métricas en vivo por WebSocket `/ws/metricas` y simulador de
  tráfico (doc 11).
- ms-pago: pasarela simulada, tópico `pagos-resultado` y retorno a tienda o consola por lista
  cerrada (doc 10).
- Compose de 22 contenedores, `infra/prueba-humo.py` y evidencias del primer arranque real en
  `evidencias/ev3-docker/` (doc 17).
- Scripts de despliegue en AWS por SSM en `despliegue/` (Ismael, integrados desde `master`).

### Cambiado

- **Antifraude**: por huella SHA-256 de la tarjeta (antes por cliente), ventana deslizante
  real (antes sólo el intento anterior), bloquea en el 4.º intento (antes en el 3.º).
- **Recomendaciones**: productos parecidos por categoría, subcategoría y marca con su motivo
  (antes el top 5 de lo ya visto); catálogo de electrónica en vez de ropa y hogar.
- Todos los consumidores: `ErrorHandlingDeserializer`, reintentos con espera exponencial, DLT y
  consumidor del DLT; productores con `acks=all`, idempotencia y callback con partición y offset.
- Consumer groups renombrados por dominio y con `concurrency` (`grupo-antifraude`,
  `grupo-recomendaciones`, `grupo-dashboard`…).
- Una base de datos por servicio (antes ms-pago y ms-recomendaciones compartían `pagos_db`);
  ms-recomendaciones pasa de MySQL a DynamoDB.
- Código de comercio de Transbank leído de configuración (`WEBPAY_CODIGO_COMERCIO`).
- README reescrito para la arquitectura nueva.

### Corregido

- Imágenes Docker: `useradd -u 1000` fallaba sobre Ubuntu 26.04; UID 10001.
- CORS de `/actuator/**` declarado donde Spring Boot lo lee; los frontends mostraban los
  servicios caídos estando arriba.
- Plantilla del DLT de ms-pago que fallaba en cada envío y trababa la partición; DLT de
  ms-recomendaciones que nunca publicaba (destapados por las pruebas con Kafka embebido).

### Eliminado

- `ProductoConfig` (catálogo fijo en código) de ms-recomendaciones.
- Grupos `payment-velocity-group` y `recommendation-consumer-group`.

## [2.0.0] — 2026-11-18

Consola React, vuelta de Webpay confirmada, endpoints de métricas y alertas, Actuator, compose
raíz y Lambdas de telemetría con Terraform (docs 01–08).

## [1.0.0] — 2026-11-13

Clúster Kafka de 3 brokers y microservicios de pago, recomendaciones y administración.
