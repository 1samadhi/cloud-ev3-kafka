# 18 · Identidad con Cognito, y tres fallos que el gateway escondía

Fecha: **26/27-11-2026**. Repos: `cloud-ev3-kafka` (rama `p4-cognito-identidad`),
`pedidos360-tienda` (rama `p4-cognito`). Cierra **P4** y completa **F1** de la EV2.

> Todo lo que dice "verificado" se comprobó **en AWS** (cuenta 851725287694,
> `18.211.30.140`, gateway `vih5lem02j`), no en local. Los dos scripts de
> verificación quedan en el repo: `infra/prueba-humo.py` y `infra/prueba-identidad.py`.

## Por qué

Pasaban dos cosas a la vez, y son la misma:

1. **La identidad era una cabecera.** El frontend mandaba `X-Usuario-Id` y los
   siete servicios le creían (`UsuarioActual`). Cualquiera con `curl` podía
   comprar, pagar o leer los pedidos de otro:
   ```bash
   curl -H "X-Usuario-Id: admin@pedidos360.cl" http://18.211.30.140:8087/api/admin/pedidos
   ```
   Las cuentas y sus claves vivían en `tienda/src/lib/auth.ts`.

2. **El gateway no estaba en uso.** `ESTADO-DESPLIEGUE-DIEGO.md` daba por
   verificado que los dos frontends entraban por el API Gateway, pero el `.env`
   de la instancia no tenía `API_BASE` (12 claves, no figuraba) y el `config.js`
   que servía la tienda apuntaba a `:8086`, `:8087`… Es decir: **F1 no estaba
   activo** en el despliegue que se iba a demostrar. El gateway se había
   verificado con `curl`, que es otra cosa que tenerlo en uso.

Al encenderlo aparecieron tres fallos que nadie podía ver desde `curl`.

## Fallo 1 · El gateway aplastaba todos los errores a 200

Cada método declaraba **una sola** *integration response*, la 200, que es el
default; API Gateway REST con integración HTTP aplica ese default a **cualquier**
código del backend.

| | directo | por el gateway |
|---|---|---|
| pedido inexistente | 404 | **200** |
| `GET` a un endpoint `POST` | 405 | **200** |
| producto inexistente | 404 | **200** |
| antifraude, 4 pagos con la misma tarjeta | `[200,200,200,429]` | **`[200,200,200,200]`** |

El cuerpo del error sí viajaba, y por eso con `curl` "se veía bien". Pero la
tienda decide por el **código**: `api.ts:83` sólo lanza con `!res.ok`, y
`checkout.ts:122` abre la pantalla de antifraude con `status === 429`. Con un 200
caía en la última rama y mostraba *"Respuesta inesperada del servicio de pagos
(HTTP 200)"*.

Dicho de otro modo: **la regla estrella de la EV3 — bloqueo por tarjeta, más de
3 intentos en 10 s — dejaba de verse en la tienda justo por pasar por el
gateway**, que es lo que pedía el docente.

`despliegue/15-respuestas-de-error.sh` declara `method response` +
`integration response` por código (400/404/500/503, más 402/429/502 en pagos),
con `selectionPattern` exacto y **replicando los mapeos CORS del 200**: una
respuesta de error sin `Access-Control-Allow-Origin` la bloquea el navegador, y
entonces el frontend ve un fallo de red en vez del error real. 121 pares
aplicados. Verificado después: `[200,200,200,429]` por el gateway, con
`Access-Control-Allow-Origin: http://18.211.30.140:8091` en el 429.

## Fallo 2 · Seis métodos que la tienda usa no estaban publicados

Las 29 rutas se habían probado con `GET`. La tienda además hace:

| Método y ruta | Para qué | Por el gateway |
|---|---|---|
| `PATCH /api/carrito/items/{id}` | cambiar cantidad | **403** |
| `DELETE /api/carrito` | vaciar | **403** |
| `DELETE /api/carrito/cupon` | quitar cupón | **403** |
| `PUT /api/carrito/envio` | **paso del checkout** | **403** |
| `PUT /api/productos/{id}/stock` | reponer (panel admin) | **403** |
| `GET /api/productos/admin/stock` | stock (panel admin) | **403** |

Un verbo no publicado responde `403 Missing Authentication Token`, **igual que
una ruta inexistente**, y en el navegador se ve como un fallo de red. Encender
`API_BASE` sin esto habría roto la compra en vivo.

Los verbos salen ahora de `tienda/src/lib/api.ts`, uno por uno. Dos mejoras en el
`14` para poder arreglarlo sin rehacer las 30 rutas: `put-method` sobre un método
existente ya no cuenta como fallo (`ConflictException`), y `SOLO_RUTA` corre un
subconjunto.

## Fallo 3 · La insignia decía "modo demo · sin backend"

Con los 21 contenedores sanos, la cabecera de la tienda mostraba **"modo demo ·
sin backend"**. Dos causas sumadas:

- con una sola base, `${base}/actuator/health` **no distingue** de quién es la
  salud: las cinco bases son la misma URL;
- el gateway no publica `/actuator`, así que responde 403 **sin cabeceras CORS**,
  y el navegador eso no lo cuenta como HTTP 403 sino como fallo de red → `caido`
  en los cinco → la etiqueta peor.

`config.ts` decide la URL según el modo (una sola base = gateway) y el `14`
declara cinco rutas `/salud/<servicio>` apuntando al `/actuator/health` de cada
puerto. Verificado: las cinco en 200 con el origen real en la cabecera.

No lo detectaba ninguna prueba: `curl` no aplica CORS y la prueba de humo va a
los puertos directos. Lo encontró abrir la tienda en un navegador.

## Lo que se construyó · identidad en el borde

**Decisión**: validar el JWT en el **API Gateway**, no con un filtro en los siete
servicios Spring (lo que decía P4, 5–8 h y tres repos la noche antes de
entregar).

- `17-cognito-pool.sh` deja el pool reproducible: user pool `pedidos360` (el
  correo es el usuario y llega verificado — en el lab no hay servidor de correo y
  un usuario sin verificar no puede entrar), app client `tienda-spa` **sin
  secreto** (es una SPA), grupos `cliente` y `admin`, y las dos cuentas de la
  demo. Las contraseñas se generan ahí y quedan **sólo** en un archivo fuera de
  los tres repos. Importa porque el pool muere con el lab.
- `16-cognito-authorizer.sh` pone un authorizer `COGNITO_USER_POOLS` y, en las
  rutas protegidas, **reescribe la cabecera**:
  `integration.request.header.X-Usuario-Id = context.authorizer.claims.email`.
  Los siete microservicios **no cambian ni una línea**: siguen leyendo
  `X-Usuario-Id`, pero ya no la elige el cliente.
- La tienda (`lib/cognito.ts`, nuevo) habla con Cognito por `InitiateAuth` sobre
  `fetch`, sin SDK: `amazon-cognito-identity-js` sumaría cientos de KB para usar
  una operación. Manda el **ID token** porque el contrato §12 dice que
  `usuarioId` es el email y el access token de Cognito no lo trae. El **rol sale
  de `cognito:groups`**: quien decide quién es admin es el pool.

### Qué queda protegido y qué no

| | |
|---|---|
| **Protegido** (11 operaciones) | `/api/pedidos` (GET, POST), `/api/pedidos/{id}`, `/api/pagos/iniciar`, `/api/notificaciones` + `/leidas` + `/{id}/leida`, `/api/admin/resumen`, `/api/admin/pedidos`, `/api/productos/admin/stock`, `PUT /api/productos/{id}/stock` |
| **Abierto** | catálogo, recomendaciones, métricas, interacciones, **carrito**, administración de Kafka/RabbitMQ |

Dos decisiones que parecen descuido y no lo son:

1. **El carrito queda abierto.** La tienda deja armar el carrito sin cuenta y al
   iniciar sesión lo traslada: `TiendaProvider.ingresar()` copia los ítems y
   **vacía el del anónimo** con el token del usuario ya logueado. Si el gateway
   reescribiera `X-Usuario-Id` en esa ruta, ese vaciado borraría el carrito
   **equivocado** (el del usuario). Además, un anónimo que no puede ni mirar el
   carrito no es una tienda.
2. **`OPTIONS` nunca lleva authorizer.** El preflight del navegador viaja sin
   `Authorization`; protegerlo haría que el navegador no llegara a mandar la
   petición real.

La administración de Kafka/RabbitMQ sigue abierta porque la consola no tiene
login (P1). Es lo pendiente obvio de esta tarea.

## Verificado el 27-11 (todo en AWS)

`infra/prueba-identidad.py` — **TODO OK**:

| Qué | Resultado |
|---|---|
| Login real de las dos cuentas contra el pool | OK, `cognito:groups` → rol |
| 4 rutas protegidas | **401** sin token · **200** con token |
| 4 rutas abiertas sin token (catálogo, recomendaciones, carrito, agregar ítem) | 200 |
| `OPTIONS` sin token en `/api/pedidos` y `/api/pagos/iniciar` | 200 |
| **Suplantación**: token de Ana + `X-Usuario-Id: admin@pedidos360.cl` | 200 y **0 pedidos ajenos**: manda el claim |
| Compra completa por el gateway con sesión real | carrito → envío (`PUT`) → pedido `P-10` → pago `AUTORIZADO` → leer el pedido |
| Antifraude por el gateway | `[200, 200, 200, 429]` |
| `prueba-humo.py` (25 comprobaciones, puertos directos) | **TODO OK** |
| Navegador real sobre `http://18.211.30.140:8091` | 24 productos pintados, `config.js` sirviendo el gateway y el client id, 0 fallos de red |

## No verificado

- **El login tecleado en el navegador.** La autenticación se probó con las
  credenciales reales por API (lo mismo que hace la tienda: `InitiateAuth`), y la
  UI logueada inyectando la sesión en `localStorage`. Falta que una persona
  escriba correo y contraseña en `http://18.211.30.140:8091/ingresar` y recorra
  el flujo; va en el ensayo (P7).
- **Renovación del token a las 8 h** (`asegurarTokenVigente`): probada con un
  doble en los tests, no contra el pool. La demo dura menos que eso.
- **La consola por el gateway.** Sigue yendo a los puertos directos: usa rutas de
  administración que el `14` no publica (`PUT /api/topicos/{n}/configuracion`,
  reset de offsets, borrado de grupos…). Afirmar que "los dos frontends pasan
  por el gateway" sería falso; la tienda sí.
- **Cognito en la consola**: no tiene pantalla de ingreso (P1).

## Trampas que costaron tiempo

1. `create-user-pool-client` con `--read-attributes`/`--write-attributes name`
   falla con `InvalidParameterException: Invalid write attributes specified`.
   Los valores por defecto ya cubren los atributos estándar: no se pasan.
2. El `aws` del PATH en Windows es un **wrapper bash** (`python -m awscli`), así
   que `subprocess` de Python nativo no lo encuentra (`WinError 2`). Hay que
   invocar el intérprete.
3. Una ruta recién publicada da 403 o 500 durante ~1 min: es propagación del
   stage.
4. Las escrituras del management API de API Gateway van a ~1 por segundo: los
   121 pares del `15` tardaron 869 s. Por eso los scripts reintentan ante
   `TooManyRequestsException` y aceptan filtros.
