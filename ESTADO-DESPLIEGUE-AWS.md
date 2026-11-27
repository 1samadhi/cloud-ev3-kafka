# Estado del despliegue en AWS — EV3 Pedidos360

Ultima actualizacion: 2026-11-27 12:55 (-03)

> Hay **dos** despliegues y este es el de **Ismael**. El de Diego
> (`ESTADO-DESPLIEGUE-DIEGO.md`, cuenta 851725287694, IP 18.211.30.140) es el que lleva
> **Cognito** y es el principal para la demo. Lo verificado en una cuenta **no es evidencia de
> la otra**: los recursos (gateway, user pool, Elastic IP) son por cuenta; el codigo y los
> scripts, en cambio, son los mismos y viven en git.

## Donde esta todo

| | |
|---|---|
| Lab | curso **183051** (item de Canvas 18057489) |
| Cuenta | 471112736485 · us-east-1 |
| Instancia | `i-0bdac4ec3cb93f53c` · **t3.large** (7,8 GB) · disco 50 GB |
| IP | **44.208.92.186** — fija, por Elastic IP: no cambia al apagar y prender |
| API Gateway | `https://khca9i6w62.execute-api.us-east-1.amazonaws.com/desarrollo` |
| Repo backend | `6e56d17` en master (local). **La instancia esta en `95ec1af`**: los cambios del 27-11 se aplicaron por override y parche, sin `git pull`, para no rearmar el stack el dia de la entrega |

### Datos de integracion (para apuntar un front a ESTE backend)

Esto es lo que hay que pasarle a quien configure un frontend contra esta cuenta. **Al front no
se le da una IP**: se le da la URL del stage. La IP solo sirve para lo que a proposito no pasa
por el gateway.

```
# lo unico que necesita el FRONT (las cinco API_* valen lo mismo)
API_BASE=https://khca9i6w62.execute-api.us-east-1.amazonaws.com/desarrollo

# lo que NO pasa por el gateway (API Gateway REST bufferea, y un stream no lo atraviesa)
WS_URL=ws://44.208.92.186:8084/ws/metricas
SSE     http://44.208.92.186:8088/api/notificaciones/stream
HOST_PUBLICO=44.208.92.186      # decide CORS_ORIGENES y el returnUrl de Webpay
KAFKA_HOST=44.208.92.186

# paginas servidas por la EC2
tienda http://44.208.92.186:8091   consola http://44.208.92.186:8090
kafka-ui http://44.208.92.186:8080 rabbitmq http://44.208.92.186:15672
```

Tres advertencias para quien lo configure:

1. **Si el front se sirve desde otro origen** (otra EC2, `localhost:5173`, un `ngrok`), ese
   origen hay que agregarlo a `CORS_ORIGENES` en el `docker-compose.override.yml` de ESTA
   instancia y recrear los servicios. Si no, Spring no devuelve cabeceras CORS y el navegador
   muestra "ms-productos no responde" con todo el backend sano. Hoy la lista acepta
   `http://44.208.92.186:8090`, `:8091` y `localhost:5173/8090/8091`.
2. **La raiz del stage responde `Missing Authentication Token`**: no es autenticacion, es que
   esa ruta no existe. Para comprobar el gateway hay que abrir una ruta real, por ejemplo
   `/desarrollo/api/productos/categorias`.
3. **La consola va a los puertos directos a proposito**: usa rutas de administracion (PUT de
   configuracion de topicos, particiones, reset de offsets, crear/borrar colas) que el `14` no
   publica. Apuntarla entera al gateway la rompe.

Esta cuenta **no tiene Cognito**: las 11 operaciones que en el lab de Diego exigen token, aca
estan abiertas, y la tienda usa el login de cuentas demo.

### Puertos

| Servicio | Puerto | Rutas |
|---|---|---|
| ms-pago | 8083 | /api/pagos · /api/alertas |
| ms-recomendaciones | 8084 | /api/recomendaciones · /api/interacciones · /api/metricas · **/ws/metricas** |
| ms-kafka-admin | 8085 | /api/admin |
| ms-productos | 8086 | /api/productos |
| ms-carrito | 8087 | /api/carrito · /api/pedidos · /api/admin/resumen |
| ms-notificaciones | 8088 | /api/notificaciones |
| ms-rabbit-admin | 8089 | /api/rabbit |
| consola | 8090 | front de operaciones |
| tienda | 8091 | front de la tienda |
| kafka-ui | 8080 | interfaz de Kafka |

## Verificado en AWS

- **La tienda entra entera por el API Gateway** (F1 de la EV2), 27-11: **35 rutas** `/api/*` y
  `/salud/*` declaradas una por una con `despliegue/14-rutas-frontend-ev3.sh`, 0 fallos; 20 de
  ellas medidas por el stage con el `Origin` de la tienda, todas 200 y **con cuerpo real** (20
  productos, 8 categorias, la query `userId` llegando). En el navegador: insignia **"backend
  conectado"**, catalogo completo y **27 llamadas al gateway, 0 a localhost**. La **consola**
  sigue directa a proposito. Evidencia: `evidencias/58`.
- **CORS por origen concreto, no comodin** (F3): el gateway reenvia `Origin` y devuelve la
  respuesta de Spring. El origen de la tienda recibe `access-control-allow-origin` con su valor
  exacto; un origen ajeno se queda **sin cabeceras**. Preflight `OPTIONS` 200 tambien en rutas
  con `{id}`.
- **Prueba de humo**: 25/25 (catalogo, interacciones, recomendaciones, carrito, pedido, pago,
  notificaciones, antifraude, metricas, administracion). Re-corrida el 27-11 tras el cambio.
- **API Gateway**: **sin `{proxy+}`** — cada recurso, metodo, parametro de ruta, query string y
  cabecera declarados uno por uno. Las 23 rutas `/v1` quedaron **superadas** por las `/api/*`
  (ver `docs/trabajo/PENDIENTES.md`: siete de las `/v1` responden 500 y no las usa nadie).
- **DLT**: dos mensajes defectuosos terminan en `pagos-resultado.DLT` con causas distinguidas (`JsonParseException` inmediato, `InvalidFormatException` tras reintento).
- **DynamoDB real** (+2): tablas `HistorialEventos`, `RecomendacionesUsuario` y `EventosOperaciones-desarrollo` (con stream NEW_IMAGE). Sin credenciales en el contenedor: usa el rol de la instancia.
- **WebSocket** (+3): handshake 101 desde el origen autorizado, 403 desde uno ajeno, y snapshots con valores que cambian.
- **P5**: las cifras del panel admin concilian al peso (`categorias + envios == ingresos`) y respetan los 14 dias. 81 pruebas de ms-carrito en verde.

Evidencias en `evidencias/50` a `54`.

## Cosas que hay que saber para no romperlo

1. **Credenciales**: vencen en ~1 hora. Recargar con `~/.hermes/duoc/aws-cred.sh` (lee el portapapeles). Los contenedores NO dependen de ellas: usan el rol de la instancia.
2. **`docker-compose.override.yml`** (en la instancia, no en git; plantilla en `despliegue/plantilla-compose-override.yml`) corrige tres cosas del compose base: `CORS_ORIGENES`, que esta fijado a localhost y en AWS rechaza al propio front; el endpoint de DynamoDB; y las cinco `API_*` de la **tienda**, que apuntan al stage del gateway. **Si la tienda carga pero no muestra datos, lo primero que hay que mirar es esto**: `curl -s http://44.208.92.186:8091/config.js` tiene que mostrar la URL del stage, no `localhost`.
3. **La tienda lleva un parche sin commit** en la instancia (`despliegue/parches/tienda-salud-por-el-gateway.patch`): con una sola base, la insignia pide `/salud/<servicio>`. Sin el parche, la cabecera dice "modo demo · sin backend" con los 21 contenedores sanos, porque el gateway no publica `/actuator` y responde 403 sin cabeceras CORS. Si se recrea la instancia, hay que volver a aplicarlo y **reconstruir** la imagen (`docker compose build tienda`), que para eso va en git.
4. **Metadata de EC2**: necesita `HttpPutResponseHopLimit` en 2 para ser alcanzable desde un contenedor. Ya esta puesto.
5. **API Gateway**: el valor de una cabecera estatica debe pasarse por **archivo JSON** (`file://`). Con la forma `clave=valor` la CLI se come las comillas simples y rechaza el mapeo.
6. **Propagacion**: tras `create-deployment` el stage tarda hasta un minuto. Un 403 o 500 recien publicado suele ser eso; `test-invoke-method` prueba la configuracion sin pasar por el stage.
7. **Entorno de la instancia**: buildx se actualizo a 0.19.3 (el 0.12 no soporta `compose build`) y se instalo Python 3.11 (la prueba de humo usa `str | None`, que 3.9 no entiende).

## Pendiente

| | Que falta |
|---|---|
| P6 | Evidencia de un pago **Webpay real** aprobado (+5) — **hecha en el lab de Diego** (`evidencias/57`, pedido `P-17`), no en este |
| P4 | Login con Cognito + filtro JWT — **hecho en el lab de Diego**. En esta cuenta **no hay user pool ni authorizer**: las 11 operaciones que alla piden token, aca estan abiertas. Los scripts para aplicarlo estan en git (`16`, `17`, `18`) y la tienda necesitaria la rama `p4-cognito` en vez del parche de salud |
| P3 | Terraform de la Lambda de telemetria — ojo: el lab no deja crear roles IAM, viene `LabRole` dado. Cerrado en `PENDIENTES.md`: la pauta no lo exige |
| — | Ensayar la demo y preparar el informe (P7) |
| — | Compra completa **tecleada en el navegador** por el gateway: las rutas responden y la prueba de humo pasa, pero el recorrido a mano no se hizo en esta cuenta |
