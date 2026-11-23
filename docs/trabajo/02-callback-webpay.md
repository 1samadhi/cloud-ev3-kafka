# 02 · El pago se confirma y vuelve al frontend

Commit `ce0b72f`.

## El defecto

En `PagosController.java` el `returnUrl` que se le entregaba a Transbank estaba
escrito a mano:

```java
"http://localhost:5173/callback"
```

Esa ruta **no existía**. Transbank hacía `POST` de `token_ws` contra esa
dirección, el token se perdía, y `POST /api/pagos/commit` nunca se alcanzaba.
Dicho de frente: **ningún pago llegaba a confirmarse**. El endpoint de commit
estaba escrito y era inalcanzable.

Se encontró comparando los endpoints del backend con lo que el frontend
consumía, y verificando que cada `http://localhost` del backend resolviera a algo
existente en alguno de los dos repositorios.

## Por qué no se arregló "creando la página en el front"

Transbank vuelve con un **POST** y el `token_ws` va en el cuerpo del formulario.
Una SPA servida como archivos estáticos **no puede leer el cuerpo de un POST**:
no hay JavaScript que vea esos bytes. Apuntar el `returnUrl` a una ruta del
frontend no podía funcionar de ninguna manera.

## La solución

El `returnUrl` apunta al propio servicio, que confirma y recién entonces redirige
al frontend por GET:

```
Webpay --POST token_ws--> :8083/api/pagos/commit --302--> <front>/callback?estado=...
```

`commit` confirma contra Transbank, actualiza el estado del cliente y redirige con
el resultado en el query string: `estado`, `orden`, `cliente`, `monto`,
`autorizacion`, `tarjeta`, `fecha`.

## Decisiones y sus motivos

| Decisión | Motivo |
|---|---|
| `/commit` acepta **GET además de POST** | según el flujo de anulación Transbank puede volver con GET |
| `token_ws` pasa a ser **opcional** | cuando el cliente abandona el formulario, Transbank no envía `token_ws` sino `TBK_TOKEN`; exigirlo hacía fallar con un 400 una anulación normal |
| La anulación cuenta como intento **no exitoso** | así el contador del antifraude no se limpia, que es el criterio que ya aplicaba `gestionarResultadoCallbackPago` |
| Siempre responde **302**, nunca JSON | el navegador queda en la consola y no mirando un cuerpo crudo |
| `PAGOS_RETURN_URL` y `FRONT_URL` como variables | en AWS el frontend no vive en `localhost` |
| El detalle de tarjeta se consulta antes de usarlo | viene nulo en transacciones rechazadas |

## Del lado del frontend

Se agregó la pantalla `/callback`, que **sólo presenta** lo que el backend ya
resolvió. La normalización de estados vive en `lib/transbank.ts` porque Transbank
y `ms-pago` no hablan el mismo vocabulario: el SDK responde `AUTHORIZED` y
`FAILED`, y el redirect usa `ANULADO` y `ERROR`.

Un estado **ausente** no se muestra como rechazo: significa que la pantalla se
abrió a mano o que el `returnUrl` no está apuntando al commit, y eso se comunica
distinto.

## Verificación

- `mvn compile` pasa, lo que confirma el uso de `getCardDetail()`,
  `getAuthorizationCode()` y `getTransactionDate()` del SDK 6.2.1.
- El redirect 302 se probó de punta a punta contra el mock: aterriza en
  `/callback` con todos los parámetros y la pantalla los muestra.

**No se probó contra Transbank de verdad.** El ambiente de integración necesita
el servicio corriendo y un navegador completando el formulario; lo que está
verificado es el camino del redirect, no el commit real.
