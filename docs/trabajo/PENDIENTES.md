# Pendientes — listos para tomar

Estado al 22-11-2026, versión `v3.0.0-rc.1`. Entrega: **viernes 27-11**.

Cada pendiente trae el contexto necesario para tomarlo sin haber estado en la conversación:
por qué importa, dónde se toca, qué hacer, cuándo está terminado y cómo comprobarlo. Antes de
empezar cualquiera, levanta el sistema (README → *Arranque*) y corre
`python infra/prueba-humo.py`: si no da `TODO OK`, arregla eso primero.

## Resumen

| ID | Pendiente | Repo | Prioridad | Esfuerzo | Sugerido para |
|---|---|---|---|---|---|
| P1 | Consola alineada al backend nuevo | cloud-ev3-front | **alta** | 4–6 h | Diego |
| P2 | `despliegue/` para la arquitectura nueva | cloud-ev3-kafka | **alta** | 3–5 h | **Ismael** (escribió los scripts) |
| P3 | Terraform de telemetría en el Learner Lab | cloud-ev3-kafka | **alta** (+3, +2) | 2–3 h | **Ismael** |
| P4 | Login con Cognito + filtro JWT | los tres | media | 5–8 h | quien termine primero |
| P5 | Panel admin de la tienda: ingresos que no cuadran | ambos | baja | 1 h | cualquiera |
| P6 | Evidencia de un pago Webpay **aprobado** | — | **alta**, 5 min | 5 min | cualquiera |
| P7 | Ensayo de la demo y evidencias de fallos | — | **alta** | 2 h | los dos |
| P8 | Doce avisos de lint en la tienda | pedidos360-tienda | baja | 1 h | cualquiera |
| P9 | Defectos conocidos (pedidos que nunca vencen, carrera del 4.º intento…) | backend | media | 2–4 h | Diego |

Si falta tiempo, se recorta en este orden: P8 → P5 → P9 → P4. P6 y P7 no se recortan.

## Cómo trabajar en paralelo sin pisarse

- **Ramas**: una por pendiente, desde la rama de integración del repo (`ev3-integracion` en
  backend y consola, `react` → `main` en la tienda). Nombre: `p2-despliegue`, `p1-consola`…
- **Dónde se integra**: los repos `1samadhi/cloud-ev3-kafka` y `1samadhi/cloud-ev3-front` son
  los oficiales. Las ramas de esta entrega se subieron a los forks `DiVilota/…` y se abrió un
  PR hacia cada original. Lo más simple: **Ismael hace merge de esos PR** y agrega a `DiVilota`
  como colaborador de sus repos; desde ahí los dos trabajan con ramas en el mismo repo.
- **Archivos compartidos**: `docker-compose.yml` y `PLAN-INTEGRACION-EV3.md` los tocan casi
  todos los pendientes. Cambios chicos, commit aparte y avisar.
- **Cada pendiente terminado** suma su documento `docs/trabajo/NN-<tarea>.md` (siguiente
  número libre: **18**) y su fila en `docs/trabajo/README.md`. Plantilla: cualquiera de los
  documentos 10–17. La sección "No verificado" es obligatoria.

---

## P1 · Consola alineada al backend nuevo

**Por qué**: la consola (`cloud-ev3-front`) no se tocó desde el 18-11 y describe el sistema
anterior. En la presentación es la pantalla de operaciones: lo que muestre tiene que coincidir
con lo que hace el backend.

**Dónde**: `cloud-ev3-front/src/`.

| Sub | Qué está mal | Qué hacer |
|---|---|---|
| P1a | `PagosScreen.tsx` dice "el antifraude cuenta por clientId" y "3 intentos / 10 s"; la ráfaga usa Webpay | textos según la regla real (misma tarjeta, **más de 3** en 10 s, bloqueo 30 min). Agregar la pasarela **SIMULADA** con campo de tarjeta: es la única forma de demostrar la regla *por tarjeta* (con Webpay la huella es la cuenta). Ráfaga de 4 con la misma tarjeta → `200 200 200 429` |
| P1b | la tabla de bloqueos muestra cliente, no tarjeta | mostrar `ultimos4` y `huellaTarjeta` (ya viene abreviada por `HuellaTarjeta.abreviar`) que ya devuelve `GET :8083/api/alertas` |
| P1c | `OperacionesScreen.tsx` no muestra alertas de fraude en vivo ni las métricas nuevas | el snapshot de `ws://localhost:8084/ws/metricas` trae `interaccionesPorMinuto`, `serieMinutos`, `topProductos`, `usuariosActivos`, `embudo` y `porTipo` (formato exacto en `docs/trabajo/11-*.md`). Pintar al menos embudo y serie; alertas desde `GET :8083/api/alertas` cada 5 s |
| P1d | `RecomendacionesScreen.tsx` usa `src/lib/catalogo.ts` (catálogo viejo de ropa/hogar) | leer el catálogo de `GET :8086/api/productos` y borrar `catalogo.ts`. Mostrar el `motivo` de cada recomendación |
| P1e | `KafkaScreen.tsx` sólo lista | agregar: editar configuración (`PUT /api/topicos/{n}/configuracion`), aumentar particiones, detalle de grupo con asignación por partición, reset de offsets y borrado de grupo. Endpoints en el README del backend |
| P1f | no hay pantalla de RabbitMQ | pantalla nueva sobre `:8089/api/rabbit/*`: clúster, colas con mensajes/consumidores, exchanges, bindings, crear/borrar, y `GET :8088/api/notificaciones/fallidas` (DLQ) |

**Configuración**: el compose ya le pasa a la consola `API_PRODUCTOS` y `API_RABBIT`, pero
`docker-entrypoint.sh`, `public/config.js` y `src/config.ts` sólo conocen `pagos`,
`recomendaciones`, `admin` y `ws`. Agregar `productos`, `rabbit` y `notificaciones`
(`API_NOTIFICACIONES`, :8088; sumarla también al compose).

**Terminado cuando**: `npm run build`, `npm test` y `npm run lint` en verde; el mock
(`mocks/servidor-mock.mjs`) actualizado con los endpoints nuevos; recorrido en el navegador
contra el sistema real de las 5 pantallas, con capturas.

## P2 · `despliegue/` para la arquitectura nueva

**Por qué**: la presentación va en AWS. Los scripts `despliegue/01…05` (por SSM) levantan la
arquitectura anterior: 3 servicios como unidades systemd, 2 tópicos con la misma
configuración, una sola base `pagos_db` y sólo la consola.

**Dónde**: `cloud-ev3-kafka/despliegue/`.

**Qué hacer** (propuesta; decide quien lo tome):

- Lo más corto: en la EC2, clonar los tres repos uno al lado del otro y correr el **mismo**
  `docker compose up -d --build` que en local. Ya está probado (doc 17), y evita mantener dos
  maneras de levantar el sistema.
- Variables que cambian en AWS (todas en `.env.example`): `KAFKA_HOST` = IP pública;
  `FRONT_URL`, `FRONT_URL_TIENDA`, `PAGOS_RETURN_URL`, `CORS_ORIGENES` y las `API_*` de los
  frontends con la IP pública en vez de `localhost`; `RABBITMQ_ERLANG_COOKIE` y
  `ANTIFRAUDE_PEPPER` distintos de los de desarrollo.
- `03-topicos-ev3.sh` debe delegar en `infra/kafka/crear-topicos.sh` (4 tópicos + 4 DLT con
  configuración por flujo), no crear los 2 tópicos viejos.
- Grupo de seguridad: abrir 8080, 8083–8091 y 15672 sólo a la IP del equipo.
- **Memoria**: el sistema usa ~4,6 GB. Una `t3.large` (8 GB) es el mínimo razonable.

**Terminado cuando**: desde un Learner Lab recién iniciado, los scripts dejan el sistema arriba
y `BASE_HOST=<ip> python infra/prueba-humo.py` da `TODO OK` desde el equipo local.

## P3 · Terraform de telemetría en el Learner Lab

**Por qué**: el +3 (dashboard por WebSocket) y el +2 (DynamoDB) se demuestran mejor en AWS
real. Hoy el dashboard funciona en local conectado directo a ms-recomendaciones; la ruta
completa DynamoDB → Streams → Lambda → API Gateway nunca se desplegó.

**Dónde**: `infra/terraform-telemetria/iam.tf`, `lambdas.tf`.

**Qué hacer**:

- El Learner Lab **no permite crear roles IAM**: reemplazar los `aws_iam_role` y sus
  `aws_iam_role_policy*` por un `data "aws_iam_role" "lab" { name = "LabRole" }` y usar
  `data.aws_iam_role.lab.arn` en las dos Lambdas.
- `terraform apply`; copiar la salida `url_websocket`.
- En ms-recomendaciones: `DYNAMODB_ENDPOINT=` (vacío), credenciales temporales del Learner Lab
  (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`) y
  `DYNAMODB_PUBLICAR_OPERACIONES=true`. La tabla por defecto ya es
  `EventosOperaciones-desarrollo`, la misma que crea Terraform.
- En la consola: `WS_URL=<url_websocket>`.

**Terminado cuando**: la pantalla Operaciones muestra métricas llegando por `wss://…amazonaws.com`
y la tabla `HistorialEventos` de DynamoDB (consola de AWS) tiene filas de la navegación.
Captura de las dos cosas.

**Trampa**: las credenciales del Learner Lab caducan cada ~4 h; al reiniciar el lab hay que
volver a pegarlas.

## P4 · Login con Cognito + filtro JWT

**Por qué**: lo pide el encargo de DUOC (login con IDaaS y JWT validado en el backend). El
diseño completo está en `PLAN-INTEGRACION-EV3.md` §12; no hay código.

**Qué hacer**, en este orden:

1. User pool con grupos `admin` y `cliente`, app client público con PKCE y Hosted UI (o por
   Terraform junto a P3).
2. Backend: `spring-boot-starter-oauth2-resource-server` en cada servicio, `jwk-set-uri` del
   pool, conversor de `cognito:groups` a `ROLE_*`, y `Component/UsuarioActual` leyendo el email
   del token. Detrás de `SEGURIDAD_HABILITADA` (por defecto `false`) para no romper la demo si
   el pool no está.
3. Tienda: Hosted UI con Authorization Code + PKCE; el ID token en `Authorization: Bearer`.
   Las cuentas demo están aisladas en `src/lib/auth.ts`: es el único archivo que hay que reemplazar.

**Terminado cuando**: con `SEGURIDAD_HABILITADA=true`, una llamada sin token a
`POST :8087/api/carrito/items` responde 401, y con el login de la tienda funciona igual que hoy.

## P5 · Panel admin de la tienda: ingresos que no cuadran

**Qué pasa**: en `/admin`, "Ingresos 14 días" suma el **total pagado** (con descuentos y
envío) y "Ventas por categoría" suma **unidades × precio de lista**. En la verificación dieron
$1.889.937 y $2.099.930.

**Dónde**: `ms-carrito/.../Service/ServicioResumenAdmin.java` (cálculo) y
`pedidos360-tienda/src/lib/admin.ts`, `src/pages/Admin.tsx` (presentación).

Además, `ingresosTotales`, `ventasPorCategoria` y `masVendidos` suman **todos** los pedidos
pagados, sin ventana: sólo `ventasPorDia` respeta los 14 días. La etiqueta "Ingresos 14 días"
y la cobertura de `rotacion()` en `lib/admin.ts` serán inexactas apenas haya pedidos más viejos.

**Qué hacer**: aplicar la ventana de 14 días a las cuatro cifras, y que ingresos y categorías
salgan de la misma base (lo recomendable: precio cobrado por
línea, prorrateando el descuento) o rotular cada una para que se entienda la diferencia.
Agregar una prueba que fije la suma.

## P6 · Evidencia de un pago Webpay aprobado

Se verificó la anulación de punta a punta, pero no un pago aprobado: hay que tipear la tarjeta
de prueba en la página de Transbank.

En la tienda (:8091), comprar cualquier producto con **Webpay Plus** → tarjeta
`4051 8856 0044 6623`, CVV `123`, RUT `11.111.111-1`, clave `123`. Debe volver a
`/callback?estado=AUTHORIZED…`, el pedido debe quedar **PAGADO** en "Mis pedidos" y debe
llegar la notificación `pedido.pagado`. Capturas en `evidencias/ev3-docker/`.

## P7 · Ensayo de la demo y evidencias de fallos

Guion sugerido (≈10 min), cada paso con lo que se muestra y el criterio que respalda:

1. `docker compose ps`: 22 contenedores; Kafka UI con los tópicos por flujo (**crit. 7**).
2. Tienda: navegar productos → la consola muestra el tráfico en vivo (**+3**); "Para ti" cambia
   con motivo (**enunciado**).
3. Carrito, cupón, envío → campana de notificaciones (**RabbitMQ**).
4. Pago Webpay aprobado (P6) → pedido PAGADO por `pagos-resultado` (**+5**, **crit. 2**).
5. Antifraude: ráfaga de 4 con la misma tarjeta → `429` y alerta en la consola (**enunciado**).
6. Fallo controlado: `docker stop ms-notificaciones`, comprar, mostrar mensajes esperando en
   RabbitMQ (management :15672), `docker start` y ver cómo se entregan.
7. Mensaje defectuoso a un DLT (**crit. 6**), que falta probar contra el broker real:
   ```bash
   docker exec -it kafka1 kafka-console-producer --bootstrap-server kafka1:29092 \
     --topic pagos-resultado --property parse.key=true --property key.separator=:
   > P-999:esto no es json
   ```
   El log de ms-carrito debe mostrar el envío al DLT y el consumidor DLT registrándolo, y el
   siguiente mensaje válido se procesa normal.
8. Admins: cambiar `retention.ms` desde ms-kafka-admin, reset de offsets de un grupo detenido
   (**crit. 3 y 9**); crear y borrar una cola desde ms-rabbit-admin.
9. Opcional: `docker stop kafka2` y mostrar que el sistema sigue (RF 3, `min.insync.replicas=2`).

Guardar las salidas y capturas de 6–9 en `evidencias/ev3-docker/`.

## P8 · Doce avisos de lint en la tienda

`npm run lint` da 0 errores y 12 avisos, todos `react(set-state-in-effect)`:
`hooks/useSalud.ts:37`, `hooks/useNotificaciones.ts:50`, `state/TiendaProvider.tsx:73, 88,
102, 188`, `pages/Producto.tsx:33`, `pages/MisPedidos.tsx:139, 161`, `pages/Admin.tsx:57`,
`pages/Inicio.tsx:142`, `pages/Checkout.tsx:148`. No rompen nada, pero el encargo pide código
sin advertencias. Derivar el valor durante el render o moverlo al evento que lo causa; los de
`TiendaProvider` son los que más conviene revisar porque disparan renders en cascada en toda
la tienda.

## P9 · Defectos conocidos

Encontrados al documentar cada servicio (detalle en el documento indicado). Ninguno rompe el
flujo de la demo; los dos primeros pueden aparecer si alguien pregunta "¿y si…?".

| Defecto | Efecto | Dónde | Doc |
|---|---|---|---|
| `PENDIENTE_PAGO` nunca vence | un 429, un 503 o un pago abandonado sin vuelta de Webpay dejan el pedido pendiente y **el stock reservado para siempre** | ms-carrito: falta un `@Scheduled` que libere reservas viejas (p. ej. 15 min) | 14 |
| Ráfaga simultánea: el 4.º puede pasar | el HTTP anticipa el bloqueo leyendo lo que el consumidor ya procesó; 4 intentos en el mismo milisegundo pueden dar 4 × 200 (el consumidor igual bloquea y alerta) | ms-pago `ServicioPagos` | 10 |
| Nunca se publica `ERROR` en `pagos-resultado` | todo lo que no es AUTHORIZED sale como `RECHAZADO` | ms-pago `PagosController` / commit | 10 |
| `x-delivery-limit` fijo en ms-carrito y configurable en ms-notificaciones | si se cambia en uno solo, el broker rechaza la redeclaración (`PRECONDITION_FAILED`) | ms-carrito `RabbitConfig` | 14, 15 |
| `UsuarioActual` anuncia leer `sub` del JWT | §12 decidió `email`; arreglar al hacer P4 | ms-productos, ms-carrito, ms-notificaciones | 13 |
| `PATCH /api/notificaciones/{id}/leida` no verifica el dueño | cualquiera marca como leída una notificación ajena (cerrar con P4) | ms-notificaciones | 15 |
| Si MySQL cae, la DLQ reencola en bucle | log ruidoso hasta que vuelve la base | ms-notificaciones, consumidor de la DLQ | 15 |
| Purgar una cola no está protegido | `POST /api/rabbit/colas/notificaciones.cliente/purgar` borra mensajes del sistema | ms-rabbit-admin `ProteccionRecursos` | 15 |
| `infra/rabbitmq/` y el compose raíz usan los mismos `container_name` | no se pueden levantar a la vez (es esperado, pero no está avisado) | `infra/rabbitmq/docker-compose.yml` | 15 |
| DynamoDB Local corre `-inMemory` | si se reinicia ese contenedor, las tablas desaparecen y los eventos van al DLT hasta reiniciar ms-recomendaciones | compose, servicio `dynamodb-local` | 11 |
