# 20 · El puerto de la tienda, y por qué las colas son tres

Fecha: **27-11-2026**, madrugada. Repo: `cloud-ev3-kafka` (rama `p4-cognito-identidad`).
Responde a dos observaciones del docente del 26-11: *"tienen una cola de más en RabbitMQ"* y
*"se ven los puertos en las páginas"*.

> Todo lo que dice "verificado" se midió **en AWS** (cuenta 851725287694, `18.211.30.140`,
> gateway `vih5lem02j`), no en local.

## 1 · La cola "de más" no existe

En el broker hay exactamente tres colas, y las tres tienen un consumidor:

```
$ docker exec rabbitmq1 rabbitmqctl list_queues name type messages consumers
notificaciones.cliente       quorum   0   1
notificaciones.auditoria     quorum   0   1
notificaciones.cliente.dlq   quorum   0   1

$ docker exec rabbitmq1 rabbitmqctl list_bindings source_name destination_name routing_key
pedidos360.notificaciones       notificaciones.auditoria     #
pedidos360.notificaciones       notificaciones.cliente       carrito.*
pedidos360.notificaciones       notificaciones.cliente       envio.*
pedidos360.notificaciones       notificaciones.cliente       pedido.*
pedidos360.notificaciones.dlx   notificaciones.cliente.dlq
```

No hay colas huérfanas, ni restos de las demos de `ms-rabbit-admin` (`demo.cola`,
`prueba.classic` se borran al final de cada demostración; se comprobó que no quedó ninguna).

| Cola | Papel | Si se borra |
|---|---|---|
| `notificaciones.cliente` | cola de trabajo: persiste la notificación y la empuja al navegador por SSE | se cae el producto |
| `notificaciones.auditoria` | binding `#`: registra todo lo que pasa por el exchange, con `SimpleMessageConverter` para poder registrar también lo mal formado | se pierde la demostración de que el exchange es **topic** (un mismo mensaje en dos colas por patrones distintos) y el log de lo que la cola cliente rechaza |
| `notificaciones.cliente.dlq` | destino del `x-dead-letter-exchange`; el consumidor lee `x-death` y guarda en `mensaje_fallido` | se pierde el criterio de DLQ con logs |

La candidata a "sobrar" es **auditoría**, porque duplica el tráfico. Es, justamente, la que
demuestra el enrutamiento por patrón: sin ella el exchange topic no se distingue de uno direct.
**Decisión: no se quita.** Si el docente insiste, el cambio son ~15 min (binding, consumidor,
fábrica `fabricaMensajesCrudos` y los tests que fijan la topología en los dos servicios).

## 2 · El puerto en la URL: qué se intentó y por qué se revirtió

La observación mezcla dos cosas:

- **Las llamadas de la aplicación** ya no llevan puerto desde el doc `18`: el `config.js` que
  sirve la tienda apunta las cinco API al gateway (`https://vih5lem02j…/desarrollo`). Eso es F1.
- **La URL de la página** sí lleva `:8091`, porque es el puerto donde la EC2 publica el nginx
  de la tienda.

Para quitarlo se parametrizó el compose (cambio que **queda en git**, con `8091` por defecto):

| Variable | Para qué |
|---|---|
| `PUERTO_TIENDA` | puerto publicado del contenedor `tienda` |
| `ORIGEN_TIENDA` | el origen **exacto** que manda el navegador; decide `CORS_ORIGENES` y `FRONT_URL_TIENDA` (la vuelta de Webpay) |

Son dos variables y no una porque **Spring compara el origen como texto**: con la tienda en el
puerto 80 el navegador manda `Origin: http://18.211.30.140` **sin** puerto, y
`http://18.211.30.140:80` no coincide. Verificado en los dos sentidos antes de desplegar:

```bash
docker compose config | grep CORS_ORIGENES   # dev  -> …:8090,…:8091
HOST_PUBLICO=18.211.30.140 PUERTO_TIENDA=80 ORIGEN_TIENDA=http://18.211.30.140 \
  docker compose config | grep CORS_ORIGENES # AWS  -> …,http://18.211.30.140
```

Con `PUERTO_TIENDA=80` en el `.env` de la instancia, **del lado del servidor funcionó todo**:

| Comprobación | Resultado |
|---|---|
| `curl http://18.211.30.140/` | 200, y `config.js` con el gateway y el client id |
| CORS del origen nuevo por el gateway | `Access-Control-Allow-Origin: http://18.211.30.140` en `GET` y en el preflight `OPTIONS` |
| CORS de un origen ajeno | sigue sin cabeceras |
| `BASE_HOST=18.211.30.140 prueba-humo.py` | **TODO OK**, 25 comprobaciones |
| `docker compose ps` | 21 contenedores arriba, `tienda` en `0.0.0.0:80->80/tcp` |

**Y aun así el navegador no abre la página.** La navegación a `http://18.211.30.140` muere con
`net::ERR_BLOCKED_BY_CLIENT` en el Chromium de prueba y tampoco carga en el Brave del equipo,
mientras que:

- un `fetch('http://18.211.30.140/config.js', {mode:'no-cors'})` desde la consola del navegador
  **sí** conecta (respuesta opaca): el servidor y la red están bien;
- las mismas páginas en puertos no estándar (`:8090`, `:8091`, `:8086`) cargan sin problema.

Es decir: el obstáculo está en el navegador y aparece **sólo** con IP pelada, HTTP y puerto por
defecto. Se revirtió a `:8091` restaurando los respaldos de la instancia
(`docker-compose.yml.respaldo-20261127-072947`, `.env.respaldo-20261127-072947`) y
`docker compose up -d`; `prueba-humo.py` volvió a dar **TODO OK** y la tienda carga con la
insignia "backend conectado" y 53 productos.

Para que la URL no tenga puerto de verdad hace falta un nombre y un certificado delante
(CloudFront, ALB o un dominio). Con CloudFront habría que mover también el **SSE** de
notificaciones (`:8088/api/notificaciones/stream`): servida por HTTPS, la página no puede abrir
un stream HTTP (contenido mixto). No se intentó.

## 3 · Grupo de seguridad: la demo no se podía mostrar fuera de casa

Hallazgo del paso anterior, y más urgente que el puerto: en `sg-09f9c2ed55290156a` los puertos
**8080 (Kafka UI), 8090 (consola), 8091 (tienda) y 15672 (RabbitMQ)** estaban abiertos sólo a
`181.226.63.205/32`, la IP de la casa. Desde la sala de clases ninguna de las cuatro páginas
habría cargado. Se abrieron `80, 8080, 8090, 8091, 15672` a `0.0.0.0/0`.

Es un lab de clase y se apaga después de la entrega; en un despliegue real esas consolas no van
abiertas a internet.

## Verificado el 27-11 (todo en AWS)

| Qué | Resultado |
|---|---|
| Las tres colas, con bindings | `list_queues` / `list_bindings` arriba: 1 consumidor cada una |
| Compose parametrizado, dev y AWS | `docker compose config` en los dos sentidos |
| Tienda en el 80 | servidor OK (200, CORS, humo 25/25), navegador NO |
| Reversión | respaldos restaurados, 21 contenedores, `prueba-humo.py` **TODO OK** |
| Tienda en `:8091` tras revertir | "backend conectado", 53 productos, `config.js` con el gateway |
| Grupo de seguridad | 80, 8080, 8090, 8091, 15672 abiertos a `0.0.0.0/0` |

## No verificado

- **Por qué exactamente el navegador bloquea `http://<IP>` sin puerto.** `ERR_BLOCKED_BY_CLIENT`
  apunta a una extensión o escudo del navegador; la hipótesis más probable es el forzado a HTTPS
  de los navegadores actuales (que no se aplica a puertos no estándar), pero **no se aisló**: no
  se probó un Chrome recién instalado sin extensiones ni con `--disable-extensions`. Tampoco se
  sabe cómo se comportará el navegador de la sala.
- **CloudFront / ALB / dominio**: no se intentó, y arrastra el problema del SSE por contenido
  mixto descrito arriba.
- **La consola** sigue entrando por los puertos directos (`:8083`–`:8089` y el WebSocket). Es
  parte de P1 y no se tocó esta madrugada.
- La instancia tiene tres archivos Java modificados a mano y sin commit (`AlertasController`,
  `AlertasService`, `SemillaCatalogo`) y está dos commits atrás del repo. Por eso los cambios de
  esta madrugada se aplicaron **sin `git pull`**, editando el compose en sitio. Hay que
  reconciliar eso después de la entrega.
