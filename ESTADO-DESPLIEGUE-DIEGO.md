# Estado del despliegue en AWS — lab de Diego

Última actualización: **2026-11-27** (sesión del jueves: Cognito y el gateway en uso de verdad).
Entrega: viernes 27-11.

> Hay **dos** despliegues. Este es el de Diego y es el **principal** para la demo.
> El de Ismael (`ESTADO-DESPLIEGUE-AWS.md`, cuenta 471112736485) queda como
> respaldo: tiene créditos pero está apagado y sólo él puede encenderlo.

## Dónde está todo

| | |
|---|---|
| Cuenta | **851725287694** · us-east-1 |
| Instancia | `i-0f5f47b9ffdefa327` · t3.large (7,8 GB) · disco 40 GB · zona us-east-1f |
| IP | **18.211.30.140** — fija, por Elastic IP (`eipalloc-0b27f261dec9c1ed9`) |
| API Gateway | `https://vih5lem02j.execute-api.us-east-1.amazonaws.com/desarrollo` |
| Cognito | user pool `us-east-1_Vaxf6Jhxx` · app client `3sf3mbsb67lo6njehp8voot8b4` · grupos `cliente`/`admin` |
| Cuentas de la demo | `ana@correo.cl` (cliente) y `admin@pedidos360.cl` (admin). Contraseñas en `pedidos360/CREDENCIALES-DEMO.txt`, **fuera de git** |
| Código en la instancia | `/opt/pedidos360/cloud-ev3-kafka` rama `p4-cognito-identidad` · `/opt/pedidos360/pedidos360-tienda` rama `p4-cognito` |
| Rama del trabajo | `DiVilota/cloud-ev3-kafka` → `p4-cognito-identidad` · `DiVilota/pedidos360-tienda` → `p4-cognito` (PR sin abrir) |

Las URL que se muestran en la demo:

```
Tienda   http://18.211.30.140:8091      Kafka UI  http://18.211.30.140:8080
Consola  http://18.211.30.140:8090      RabbitMQ  http://18.211.30.140:15672
```

## Verificado el 27-11 (todo en AWS, no en local)

| Qué | Resultado |
|---|---|
| `docker compose ps -a` | 21 corriendo + `crear-topicos` Exited(0) |
| `BASE_HOST=18.211.30.140 prueba-humo.py` | **TODO OK** (25 comprobaciones) |
| `prueba-identidad.py <client-id>` | **TODO OK** (identidad, suplantación, compra por el gateway) |
| `config.js` de la tienda | las 5 API en la URL del gateway **y** el client id de Cognito |
| **La tienda en el navegador** | insignia **"backend conectado"**, 51 productos, sesión de Ana con sus pedidos, `/admin` por el claim `cognito:groups`, 0 errores de consola |
| Rutas protegidas | **401** sin token · **200** con token (11 operaciones) |
| Suplantación por `X-Usuario-Id` | ya no funciona: el gateway reescribe la cabecera con `claims.email` |
| Antifraude por el gateway | `[200, 200, 200, 429]`, con CORS en el 429 |
| CORS por el gateway | el origen **real**, no `*`; un origen ajeno no recibe cabeceras |
| Query strings y `X-Usuario-Id` por el gateway | llegan (`interaccionesPorMinuto` 0 → 3) |
| Las 3 colas de RabbitMQ | `evidencias/55`: cliente, auditoría y DLQ con mensajes reales |

## Las observaciones de la EV2, cerradas

| | Observación | Cómo quedó |
|---|---|---|
| F1 | API Gateway para el frontend | `API_BASE` **en el `.env` de la instancia**: la **tienda** entra entera por el gateway (35 rutas, sin `{proxy+}`), verificado en el navegador. La **consola** sigue directa: usa rutas de administración que el `14` no publica |
| F2 | No usar proxy en backend | ningún `proxy_pass`; el navegador llama a la API, no a nginx |
| F3 | CORS con la ruta de la API | el gateway reenvía `Origin` y devuelve la respuesta CORS del servicio, también en los errores |
| F4 | Todas las colas funcionan | `evidencias/55` (RabbitMQ) + `evidencias/51` (DLT de Kafka) |

## Cosas que hay que saber para no romperlo

1. **La Elastic IP no cambia** al apagar y prender. Cuesta ~US$0,12/día mientras
   la instancia está apagada, y a cambio no hay que reapuntar nada.
2. **Si la demo se ve rara, lo primero que hay que mirar es el `.env` de la instancia.** El 26-11
   el MD daba por hecho que los frontends entraban por el gateway y `API_BASE` **no estaba puesta**:
   el `config.js` que servía la tienda apuntaba a los puertos. Comprobación de 5 segundos, y es la
   única que vale:
   ```bash
   curl -s http://18.211.30.140:8091/config.js
   ```
   Tienen que salir la URL del gateway y `cognitoClientId`.
3. **`API_BASE`, `HOST_PUBLICO` y `COGNITO_*`** viven en el `.env` de la instancia (no en git).
   `HOST_PUBLICO` decide CORS, el `returnUrl` de Webpay y el WebSocket;
   `API_BASE` decide por dónde entran los frontends. Vaciar `API_BASE` devuelve
   el sistema al modo desarrollo (un puerto por servicio) sin tocar código, pero
   **también apaga F1**: no lo hagas sin saberlo. `COGNITO_CLIENT_ID` vacío deja
   la tienda sin login, con el catálogo navegable.
4. **Dos cosas NO pasan por el gateway, a propósito**: el WebSocket de métricas y
   el stream SSE de notificaciones. Una integración HTTP de API Gateway REST
   bufferea la respuesta, así que un stream no la atraviesa. Van directos.
5. **Una ruta recién publicada da 403 o 500 durante ~1 minuto**: es propagación
   del stage, no un error de configuración. Esperar y reintentar.
6. **El AWS CLI de Windows escribe CRLF.** Un id capturado del CLI con `\r` al
   final rompe la firma SigV4 y AWS responde
   `UnrecognizedClientException: security token invalid`, que no tiene nada que
   ver con las credenciales. Los scripts filtran con `tr -d '\r'`.
7. **La identidad la valida el gateway, no los servicios.** El authorizer de
   Cognito protege 11 operaciones y reescribe `X-Usuario-Id` con `claims.email`.
   Los puertos directos (8083–8089) **siguen sin pedir token**: son el destino de
   las integraciones del gateway y están abiertos a `0.0.0.0/0`. O sea, la
   protección vale para quien entra por el gateway, que es por donde entra la
   tienda. Cerrar eso del todo es VPC Link + NLB, varias horas más.
8. Los puertos **8083–8089 están abiertos a `0.0.0.0/0`** porque el API Gateway
   no tiene IPs fijas y necesita alcanzarlos. 8090, 8091, 15672 y los brokers
   siguen restringidos a la IP del equipo: **el viernes, desde otra red, hay que
   agregar esa IP al security group** o no cargarán los frontends.

## Qué falta

| | Qué |
|---|---|
| **P1** | Consola alineada al backend nuevo (Diego, en curso). Se le suman dos cosas: entra por los puertos directos (le faltan rutas de administración en el gateway) y no tiene pantalla de ingreso con Cognito |
| **P6** | Evidencia de un pago Webpay **aprobado** — 10 min, en `http://18.211.30.140:8091` |
| **P7** | Ensayo de la demo y evidencias de fallos (los dos). Incluir el **login tecleado** en la tienda: la autenticación está verificada por API y con la sesión inyectada, no escribiéndola a mano |
| — | Corregir `evidencias/50`: dice que las 12 rutas `/v1` dan 200. Re-medido el 27-11: **siete dan 500** y `/v1/recomendaciones` pierde la query string. `/v1` quedó superado por las rutas `/api/*` del `14`; lo honesto es decirlo en el documento |
| — | Abrir el PR de `p2b-gateway-frontend` hacia `1samadhi/cloud-ev3-kafka` |

## Cómo retomar

```bash
# 1. Start Lab y pegar el bloque de AWS Details en ~/.aws/credentials
#    (sólo [default] y las tres aws_*; si se cuelan AWSAccountId/Region, falla)
# 2. Prender la instancia (la IP NO cambia)
aws ec2 start-instances --region us-east-1 --instance-ids i-0f5f47b9ffdefa327
# 3. Comprobar que el sistema volvió solo (restart: unless-stopped)
BASE_HOST=18.211.30.140 python infra/prueba-humo.py     # debe dar TODO OK
# 4. Comprobar la identidad (el user pool NO muere al apagar la instancia,
#    pero sí cuando caduca el lab: ahí hay que correr despliegue/17 y 16)
python infra/prueba-identidad.py 3sf3mbsb67lo6njehp8voot8b4   # debe dar TODO OK
# 5. Y lo que de verdad prueba que el gateway está en uso:
curl -s http://18.211.30.140:8091/config.js   # URL del gateway + cognitoClientId
# 6. Al terminar, apagar: es lo que consume crédito
aws ec2 stop-instances --region us-east-1 --instance-ids i-0f5f47b9ffdefa327
```
