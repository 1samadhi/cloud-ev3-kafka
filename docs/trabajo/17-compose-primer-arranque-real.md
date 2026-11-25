# 17 · El sistema completo levantado de verdad, y lo que eso destapó

Commits `6116e80` (compose de 22 contenedores), `5d55d50` (UID de los Dockerfiles),
`4c027b4` (CORS de Actuator), `e8016ed` (prueba de humo). Evidencia en
`evidencias/ev3-docker/`.

## El problema

Hasta el 22-11 **nada se había ejecutado junto**: en la máquina de trabajo no había Docker, y
cada servicio estaba probado por separado (Kafka embebido, H2, mocks). El compose estaba
validado sólo estáticamente. Lo que falla en la integración —puertos, CORS, imágenes base,
orden de arranque— no aparece en ninguna de esas pruebas.

Bloqueo previo: Docker Desktop no arrancaba con *"Windows hypervisor is not running"*. La CPU
y el firmware tenían la virtualización activa y WSL2 estaba instalado; la causa era
`hypervisorlaunchtype Off` en el arranque de Windows (lo apagan muchas guías para juegos). Se
corrigió con `bcdedit /set hypervisorlaunchtype auto` y un reinicio.

## Qué se hizo

```
docker-compose.yml        22 servicios: ZK×3, Kafka×3, crear-topicos, kafka-ui, RabbitMQ×3,
                          MySQL, DynamoDB Local, 7 microservicios, tienda, consola
infra/validar-compose.py  chequeos estáticos ampliados a RabbitMQ, bases y CORS de ambos frentes
infra/prueba-humo.py      recorre la compra contra los servicios reales (25 comprobaciones)
ms-*/Dockerfile           usuario no-root con UID 10001
ms-*/application.yaml     management.endpoints.web.cors con CORS_ORIGENES
ms-*/Config/CorsConfig    se quita el mapeo /actuator/** que nunca se aplicaba
evidencias/ev3-docker/    13 capturas y las salidas de la verificación
```

### Defecto 1 — seis imágenes no construían

`eclipse-temurin:21-jre` pasó a Ubuntu 26.04, que trae el usuario `ubuntu` con UID 1000.
`useradd -m -u 1000 appuser` falla con código 4 (UID repetido). ms-recomendaciones usaba 1001 y
pasaba por casualidad. Se fijó **10001** en los siete.

### Defecto 2 — la tienda decía "modo demo" con todo arriba

Los endpoints de Actuator **no pasan por el `CorsConfig` de MVC**: los atiende su propio
handler mapping, que sólo lee `management.endpoints.web.cors.*`. El mapeo `/actuator/**` de los
siete `CorsConfig` era código muerto: `/api/**` respondía con `Access-Control-Allow-Origin` y
`/actuator/health` no, así que el navegador bloqueaba el sondeo de salud. Era silencioso porque
los frontends sólo se habían probado contra su mock, que manda CORS permisivo, y `curl` no
aplica CORS.

## Decisiones y su porqué

| Decisión | Porqué |
|---|---|
| UID 10001 fijo, no "cualquiera libre" | fuera del rango que reservan las imágenes base; reproducible entre builds |
| CORS de Actuator en `application.yaml` con la **misma** `CORS_ORIGENES` | una sola variable para los dos mecanismos; cambiar un origen no puede dejar uno desalineado |
| Sólo `GET` en el CORS de Actuator | health se lee; nada que modificar |
| La prueba de humo verifica efectos asíncronos, no códigos HTTP | "healthy" sólo prueba `/actuator/health`; lo que importa es que el evento llegue al consumidor, que el stock cambie y que el mensaje salga de la cola |
| Tarjeta Luhn distinta en cada corrida | el bloqueo dura 30 min; con una tarjeta fija la 2.ª corrida recibía 429 desde el primer intento, que es comportamiento correcto, no una falla |
| Un servicio caído se reporta como FALLA del paso | sin eso, `URLError` cortaba la prueba con un traceback y escondía el resto |
| Evidencia commiteada en `evidencias/` | el docente puede revisarla sin levantar 22 contenedores |

## Verificación

| Qué | Resultado |
|---|---|
| `docker compose config` | válido |
| `docker compose build` | 9 imágenes (tras el defecto 1) |
| `docker compose up -d` | 22 contenedores; `crear-topicos` Exited (0); el resto healthy |
| `kafka-topics --describe` | 4 tópicos + 4 DLT con la configuración del contrato |
| `kafka-consumer-groups --list` | los 8 grupos activos; `grupo-recomendaciones` con 3 miembros |
| `rabbitmqctl cluster_status` | 3 nodos, sin particiones; colas quorum con DLQ y los 5 bindings |
| `python infra/prueba-humo.py` | **25/25**, dos corridas seguidas |
| ¿La prueba puede fallar? | con `docker stop ms-notificaciones`: falla sólo el paso de notificaciones; con todo apagado: corta con "el sistema no está levantado" |
| Resiliencia RabbitMQ | consumidor caído: la compra queda PAGADA, 3 mensajes esperan en `notificaciones.cliente`, se entregan al volver (cola a 0) |
| CORS de Actuator (curl con `Origin`) | los 7 puertos responden `Access-Control-Allow-Origin` para :8091; un origen ajeno no recibe cabeceras |
| Tienda en navegador (:8091) | insignia "backend conectado", login demo, detalle con recomendaciones, carrito, cupón, Webpay real, anulación → `/callback?estado=ANULADO`, pedido rechazado y stock liberado, panel admin |
| Consola en navegador (:8090) | dashboard conectado a `ws://localhost:8084/ws/metricas` con métricas en vivo; pantalla Kafka con 3 brokers, 8 tópicos, 8 grupos |
| RAM | ~4,6 GB para los 22 contenedores |

### No verificado

- **Pago Webpay aprobado**: requiere tipear la tarjeta de prueba en el formulario de Transbank.
  Se verificó el camino de anulación de punta a punta.
- La caída de **un nodo** RabbitMQ o de **un broker** Kafka con el sistema funcionando.
- Un mensaje real llegando a un DLT o a la DLQ en el broker (sólo en pruebas unitarias).
- DynamoDB real de AWS (sólo DynamoDB Local), Lambdas y API Gateway desplegados.
- El despliegue en AWS (`despliegue/` describe la arquitectura anterior).

## Pendiente

En `PENDIENTES.md`: actualizar la consola, adaptar `despliegue/` y Terraform al Learner Lab,
Cognito, y el descuadre de ingresos del panel admin de la tienda.

## Trampas para quien siga

- El primer `docker compose up` tarda varios minutos creando contenedores (descarga de
  imágenes); no está colgado.
- Un frontend que dice "modo demo" con los servicios arriba es casi siempre CORS: prueba con
  `curl -D - -H 'Origin: http://localhost:8091' http://localhost:8083/actuator/health`.
- Un `Exited (0)` en `crear-topicos` es lo esperado: es un contenedor de un solo uso.
- Con el hipervisor activo algunos juegos rinden algo menos; para apagarlo:
  `bcdedit /set hypervisorlaunchtype off` y reiniciar (Docker deja de funcionar).
- La prueba de humo crea un usuario `humo-<timestamp>` por corrida: deja pedidos y alertas en las
  bases de desarrollo. Para empezar limpio, `docker compose down -v`.
