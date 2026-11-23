# 09 · Un contrato antes que el código: la integración de la EV3

Commits `ec7d8ee` (contrato) y `5f2dfbc` (§12, Cognito). Es la base de las tareas 10 a 17:
cada una implementa una sección de `PLAN-INTEGRACION-EV3.md`.

## El problema

Había que pasar de 3 microservicios con 2 tópicos a 7 servicios con Kafka, RabbitMQ y dos
frontends, construidos **en paralelo** por varios agentes y en 5 días. Sin un acuerdo previo,
cada servicio habría inventado sus propios nombres de tópico, campos de DTO y puertos, y la
integración se habría descubierto rota recién al levantar todo.

Además, al contrastar los dos documentos de la evaluación con el código, aparecieron
incumplimientos que no se veían leyendo sólo el código:

| Fuente | Exige | El código hacía |
|---|---|---|
| Enunciado | antifraude por **la misma tarjeta**, más de 3 veces en 10 s | contaba por cliente, comparaba sólo con el intento anterior y bloqueaba en el 3.º |
| Enunciado | recomendar productos **parecidos** a lo consultado | devolvía el top 5 de lo mismo que el usuario ya había visto |
| Enunciado | **Productos y Carrito** publican la navegación | no existían esos servicios |
| Pauta EP5 crit. 6 (12 %) | manejo de errores: validación, reintentos, sin interrumpir el sistema | nada: un JSON roto dejaba al consumidor reintentando el mismo mensaje sin fin |
| Pauta EP5 crit. 7 (12 %) | configuración de tópicos **ajustada a cada flujo** | los dos tópicos con configuración idéntica |
| Pauta EP5 crit. 3 y 9 | el admin **modifica** tópicos y opera consumer groups | sólo listaba |
| EV2 (pendiente) | RabbitMQ: productores, consumidores, DLQ con logs, admin, clúster | no había nada |

Estimación del puntaje de la pauta antes de empezar: ~60 %.

## Qué se hizo

`PLAN-INTEGRACION-EV3.md`, 14 secciones:

```
§0  pauta y enunciado → dónde se cumple cada criterio
§1  mapa de servicios y puertos (8083–8089, frentes 8090/8091, dev 5173/5174)
§2  tópicos por flujo, consumer groups y DTOs de evento
§3  reglas transversales: config Kafka, productores, consumidores, errores, estilo, pruebas
§4–§8  ms-pago · ms-recomendaciones · ms-kafka-admin · ms-productos · ms-carrito
§9–§11 RabbitMQ: topología · ms-notificaciones · ms-rabbit-admin
§12 Cognito + JWT (diseño, sin implementar)
§13 compose · §14 calendario y línea de corte
```

## Decisiones y su porqué

| Decisión | Porqué |
|---|---|
| Contrato escrito antes de cualquier código | siete agentes en paralelo; el documento es lo único que comparten |
| `ms-productos` y `ms-carrito` en vez de un `ms-pedidos` | el enunciado nombra a "los microservicios de Carrito y Productos" como productores de eventos |
| REST para comandos, Kafka para eventos, RabbitMQ para notificaciones | reservar stock necesita respuesta inmediata; la analítica necesita *replay* y *fan-out*; una notificación necesita entrega por mensaje con DLQ. Y así son dominios de falla independientes |
| Un fallo al publicar en Kafka o RabbitMQ **no** rompe la operación (salvo el intento de pago) | la compra no puede depender de la analítica; en ms-pago publicar el intento *es* el antifraude, por eso ahí se responde 503 |
| Tópico nuevo `pagos-resultado` | sin él, el pedido se marcaba pagado antes de pagar; con él, ms-carrito reacciona al resultado real |
| Cada servicio con su propia copia de los DTOs, sin librería compartida | una librería común acopla los despliegues; `ADD_TYPE_INFO_HEADERS=false` hace que el consumidor no dependa del paquete Java del productor |
| Identidad por header `X-Usuario-Id`, resuelta en una sola clase `UsuarioActual` | Cognito llega al final; cuando llegue, cambia esa clase y nada más |
| Línea de corte explícita (§14) | con 5 días, decidir antes qué se sacrifica: Lambda en AWS → Cognito → panel admin; nunca la pauta ni el flujo de compra |
| §12: el front manda el **ID token** y `usuarioId = email` | el ID token trae email y `cognito:groups`; el access token de Cognito no trae el email |

## Verificación

| Qué | Resultado |
|---|---|
| Los 7 servicios construidos contra el contrato | `mvn -B test` BUILD SUCCESS en los siete: 406 pruebas |
| Integración real con `docker compose up` (doc 17) | 22 contenedores sanos; prueba de humo 25/25 |
| Tópicos y grupos del sistema levantado contra §2 | coinciden (`evidencias/ev3-docker/README.md`) |

### No verificado

- §12 (Cognito) es diseño: no hay código ni user pool creado.
- La estimación de puntaje es lectura propia de la pauta, no una corrección del docente.

## Pendiente

Ver `PENDIENTES.md`. Las desviaciones de implementación respecto del contrato están anotadas en
el documento de cada servicio (10 a 16).

## Trampas para quien siga

- Si cambias un nombre de tópico, grupo o campo de evento, cámbialo **primero en el contrato** y
  después en todos los servicios que lo usan: no hay librería compartida que falle al compilar.
- Los puertos 5173/5174/8090/8091 están en `CORS_ORIGENES` de los siete servicios y en
  `FRONT_URL*` de ms-pago. Mover un frontend obliga a tocar ambos.
