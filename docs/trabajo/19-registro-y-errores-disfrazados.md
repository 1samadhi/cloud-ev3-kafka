# 19 · Registro de cuentas, y tres errores que se disfrazaban de "microservicio caído"

Fecha: **27-11-2026**. Repos: `cloud-ev3-kafka` (rama `p4-cognito-identidad`),
`pedidos360-tienda` (rama `p4-cognito`). Continúa el `18`.

> Todo lo que dice "verificado" se comprobó **en AWS** (cuenta 851725287694,
> `18.211.30.140`, gateway `vih5lem02j`), no en local. La comprobación nueva
> queda en el repo: `infra/prueba-registro.py`.

## Por qué

El `18` dejó la identidad validada en el borde y la tienda entrando por el API
Gateway. Al usarla **como la usa una persona** — abrir el navegador, crear una
cuenta, comprar — aparecieron cuatro cosas:

1. no había forma de **crear** una cuenta: la única era que alguien la diera de
   alta con el AWS CLI;
2. la tienda decía *"el servicio no responde"* en pantallas distintas, con los
   21 contenedores sanos y respondiendo 200 por `curl`.

Los tres fallos de abajo son el mismo fallo con tres caras: **un error de
autorización o de CORS, visto desde el navegador, es indistinguible de un
servicio caído**. Eso es lo que hacía que el sistema pareciera "mañoso": se
revisaba el microservicio, que estaba perfecto, y no la cabecera que faltaba.

## Fallo 1 · El preflight moría en las rutas con parámetro

`OPTIONS /api/productos/{id}` respondía **500**; `OPTIONS /api/productos`, 200.

La URI de la integración lleva `{id}`. Si no se declara **de dónde sale** ese
parámetro, API Gateway no puede armar la URL del backend y falla — y el `14`
declaraba los parámetros de ruta en los métodos reales (`GET`, `PUT`…) pero
**no en el `OPTIONS`**. El navegador, ante un preflight en 500, no manda la
petición real: la tienda ve un fallo de red y reporta el servicio caído.

Sólo se nota con una cabecera que **dispare** preflight (`Authorization`,
`X-Usuario-Id`) sobre una ruta con parámetro. Por eso no lo vio nadie: `curl`
no hace preflight, y las rutas sin parámetro funcionaban.

En `despliegue/14-rutas-frontend-ev3.sh`, `preflight()` ahora recibe la ruta,
extrae sus `{parámetros}` y los declara también en el `OPTIONS`. Con una
trampa: un `OPTIONS` **que ya existía** conserva los parámetros viejos y
`put-method` no lo sobrescribe (`ConflictException`), así que se agregan uno a
uno con `update-method` / `update-integration` usando **`op=add`** y no
`op=replace`: el parámetro todavía no existe, y un `replace` sobre algo ausente
no hace nada **y no da error** — se vería como éxito.

Verificado el 27-11 por el gateway, con `Origin` y `Access-Control-Request-*`:

| Preflight | Antes | Ahora |
|---|---|---|
| `OPTIONS /api/productos/101` | 500 | **200** |
| `OPTIONS /api/pedidos/P-1` | 500 | **200** |
| `OPTIONS /api/productos` (control, sin parámetro) | 200 | 200 |

## Fallo 2 · El pago era la única llamada sin token

Síntoma: *"No se pudo iniciar el pago: el servicio no responde"*, con Webpay y
con la pasarela simulada, y con ms-pago sano (200 directo **y** por el gateway,
preflight correcto).

`pagos.iniciar` era la **única** llamada de la tienda que no pasaba por
`conUsuario`, así que no mandaba `Authorization`. Y `POST /api/pagos/iniciar`
quedó protegido con el authorizer en el `18`. El 401 resultante lo fabrica el
gateway **sin cabeceras CORS** (fallo 3), el navegador lo descarta, `fetch`
lanza y el `catch` reporta "el servicio no responde".

Arreglo de una línea en `tienda/src/lib/api.ts`:
`conUsuario(peticion.clientId, cuerpoJson('POST', peticion))`. `clientId` **es**
el `usuarioId` por contrato (§6), así que no hubo que cambiar la firma ni tocar
las pantallas.

## Fallo 3 · Los errores del borde no llevaban CORS

El `15` declara las respuestas de error **por método**, y eso cubre lo que
devuelven los servicios. Pero un 401 del authorizer no lo genera el servicio:
lo genera el gateway, y no pasa por esos mapeos. Mismo caso para un 403 de ruta
no publicada y para los `DEFAULT_4XX` / `DEFAULT_5XX`.

Sin `Access-Control-Allow-Origin` el navegador descarta la respuesta: **cualquier
fallo de autorización, token vencido incluido, se ve como un fallo de red**.

`16-cognito-authorizer.sh` configura ahora las cuatro *gateway responses*
(`UNAUTHORIZED`, `ACCESS_DENIED`, `DEFAULT_4XX`, `DEFAULT_5XX`) con cabeceras
CORS y un cuerpo JSON (`$context.error.messageString`). Estaba aplicado a mano
desde el 27-11 de madrugada; **guionizarlo era obligatorio porque el pool y el
gateway mueren con el lab**.

Verificado: `POST /api/pagos/iniciar` sin token → `401` con
`Access-Control-Allow-Origin` presente y cuerpo `{"message":"Unauthorized"}`.

> **Matiz honesto sobre F3 de la EV2.** Aquí el origen va como comodín `*`,
> mientras que el `14`/`15` reflejan el origen exacto. No es una decisión: las
> *gateway responses* sólo aceptan valores **estáticos**, no
> `method.request.header.Origin`. El comodín aparece únicamente en los errores
> que fabrica el gateway; las respuestas de los servicios siguen llevando el
> origen real. Es admisible porque la tienda autentica con la cabecera
> `Authorization` y no con cookies: no hay credenciales que `*` pueda exponer.

## Lo que se construyó · registro de cuentas

### El trigger, porque en el lab no hay correo

Cognito deja al usuario en `UNCONFIRMED` y le manda un código por correo. En el
Learner Lab no hay SES fuera del sandbox: **ese correo no llega**. El usuario se
registra, parece que todo salió bien, y al entrar recibe
`UserNotConfirmedException` sin forma de confirmarse. Para una demo es peor que
no tener registro.

`despliegue/18-cognito-registro.sh` crea una Lambda `PreSignUp` de diez líneas
que autoconfirma la cuenta y marca el correo como verificado. Es un gancho
**oficial** de Cognito, no un parche del frontend. La propia función dice en su
docstring que **en producción esto se borra**: autoconfirmar significa que
cualquiera puede registrar una dirección que no es suya.

Dos cosas que el script tuvo que resolver:

- **`update-user-pool` reemplaza, no fusiona.** Si no se repiten las opciones que
  el pool ya tenía (correo autoverificado, política de contraseña), vuelven a
  sus valores por defecto y el pool queda distinto del que creó el `17`. El
  script las vuelve a pasar.
- **`zip` no existe en el git-bash de Windows.** Se empaqueta con el `zipfile`
  de Python si no hay `zip`.

El rol es `LabRole` por ARN: el lab no deja crear roles IAM.

### La pantalla

| Dónde | Qué |
|---|---|
| `Header.tsx` | dos accesos: *Ingresar* y *Crear cuenta* |
| `Ingresar.tsx` | **dos pestañas, no una ruta nueva**: una ruta aparte perdería el `?volver=` y el `?motivo=compra` que trae la URL cuando el checkout manda a identificarse |
| `cognito.ts` | `registrar()` (SignUp) y los mensajes de `UsernameExists` / `InvalidPassword` en castellano |
| `auth.ts` | `crearCuenta()`: registra y entra de inmediato — el trigger ya confirmó la cuenta, así que pedirle al usuario que "revise su correo" sería mentirle. Si algún día el trigger no estuviera, lo dice en vez de inventar una sesión |
| `TiendaProvider.tsx` | el traslado del carrito anónimo se reusa de `ingresar()`: es justo el caso de quien arma el carrito sin cuenta y la crea en el checkout |

El formulario **dice la política del pool antes** de que Cognito la rechace, y
cuenta cuántos caracteres faltan: si el requisito aparece recién como error del
servidor, uno escribe tres contraseñas hasta adivinar qué falta.

## Lo que se construyó · ilustraciones del catálogo

20 SVG generados (`tienda/scripts/generar-imagenes.py`, **84 KB** en total) en la
paleta de la tienda, versionados en `public/img/`. Antes el catálogo mostraba
marcos vacíos. Son SVG y no fotos por tres razones: pesan casi nada, no
dependen de ningún servicio externo durante la demo, y no hay problema de
licencia. El script y `scripts/catalogo-respaldo.json` quedan en el repo para
regenerarlas si cambia el catálogo.

## Verificado el 27-11

| Qué | Cómo | Resultado |
|---|---|---|
| Registro de punta a punta contra el pool real | `infra/prueba-registro.py` | **TODO OK** (5/5) |
| ↳ alta aceptada y **confirmada sin correo** | `SignUp` → `UserConfirmed=True` | OK |
| ↳ login inmediato con la cuenta nueva | `InitiateAuth` | OK, el ID token trae `email`, `name`, `email_verified` |
| ↳ correo repetido | segundo `SignUp` | rechazado (`UsernameExistsException`) |
| ↳ contraseña débil | `SignUp` | rechazada por la política del pool |
| ↳ limpieza | la cuenta de prueba se borra incluso si algo falla | OK |
| Preflight en rutas con parámetro | `curl -X OPTIONS` con `Origin` + `Access-Control-Request-*` | 200 (antes 500) |
| 401 del authorizer con CORS | `POST /api/pagos/iniciar` sin token | 401 **con** `Access-Control-Allow-Origin` |
| Las 4 *gateway responses* quedan aplicadas | `get-gateway-response` × 4 | las cuatro con cabeceras |
| Tienda | `npm test`, `tsc`, `npm run build` | **64 tests** (4 nuevos), build en verde |
| Sistema arriba | tienda `:8091`, consola `:8090`, `GET /api/productos` por el gateway | 200, 200, 200 |

## No verificado

- **El registro tecleado en el navegador.** Se probó por API, que es exactamente
  lo que hace la tienda (`SignUp` + `InitiateAuth` sobre `fetch`), pero nadie ha
  escrito todavía correo y contraseña en
  `http://18.211.30.140:8091/ingresar` → pestaña *Crear cuenta*.
- **Un pago Webpay aprobado** sigue sin evidencia (P6): se verificó la anulación
  de punta a punta y el pago por la pasarela simulada, no el AUTHORIZED tecleando
  la tarjeta de prueba en Transbank.
- **El mensaje de contraseña débil no siempre es el específico.** Una contraseña
  **corta** la rechaza Cognito con `InvalidParameterException`, no con
  `InvalidPasswordException`, así que el usuario ve "Revisa el correo y la
  contraseña" en vez del detalle de la política. El formulario ya avisa del
  mínimo antes de enviar, así que en la práctica casi no se alcanza.
- **El trigger `PreSignUp` no tiene prueba de que esté desenganchado en
  producción**: es una nota en el código, no un control.
- **La consola** sigue por los puertos directos y sin login (P1). Nada de este
  documento la toca.

## Trampas que costaron tiempo

1. **Un error de CORS y un microservicio caído se ven igual** desde el
   navegador, y `curl` no distingue porque no aplica CORS. Los tres fallos de
   arriba se buscaron primero en los servicios. Regla para la próxima: si la
   tienda dice "no responde" pero `curl` da 200, **el problema está en el borde**
   (preflight, authorizer o cabeceras), no en el servicio.
2. `op=replace` sobre un *request parameter* que **no existe** no falla: no hace
   nada y el script reporta éxito. Para agregar va `op=add`.
3. `update-user-pool` **reemplaza** la configuración completa del pool.
4. El `aws` del PATH en Windows es un wrapper bash (`python -m awscli`): desde
   Python nativo hay que invocar el intérprete (ya estaba en el `18`, volvió a
   aparecer en `prueba-registro.py`).
