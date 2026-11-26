# Estado del despliegue en AWS — lab de Diego

Última actualización: **2026-11-26** (sesión del miércoles). Entrega: viernes 27-11.

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
| Código en la instancia | `/opt/pedidos360/cloud-ev3-kafka`, rama `p2b-gateway-frontend` |
| Rama del trabajo | `DiVilota/cloud-ev3-kafka` → `p2b-gateway-frontend` (PR sin abrir) |

Las URL que se muestran en la demo:

```
Tienda   http://18.211.30.140:8091      Kafka UI  http://18.211.30.140:8080
Consola  http://18.211.30.140:8090      RabbitMQ  http://18.211.30.140:15672
```

## Verificado el 26-11 (todo en AWS, no en local)

| Qué | Resultado |
|---|---|
| `docker compose ps -a` | 21 corriendo + `crear-topicos` Exited(0) |
| `BASE_HOST=18.211.30.140 prueba-humo.py` | **TODO OK** (25 comprobaciones) |
| API Gateway: rutas `/api/*` | **29 declaradas**, responden 200 |
| `config.js` de la tienda | las 5 API apuntan a la URL del gateway |
| CORS por el gateway | `Access-Control-Allow-Origin: http://18.211.30.140:8091` — el origen **real**, no `*`; un origen ajeno no recibe cabeceras |
| Query strings por el gateway | `/api/recomendaciones?userId=…` devuelve el catálogo (antes se perdía el parámetro) |
| Cabecera `X-Usuario-Id` por el gateway | 3 detalles de producto → `interaccionesPorMinuto` 0 → **3**: la cabecera atraviesa, el evento llega a Kafka y la telemetría lo recibe |
| Las 3 colas de RabbitMQ | `evidencias/55`: cliente, auditoría y DLQ con mensajes reales |

## Las observaciones de la EV2, cerradas

| | Observación | Cómo quedó |
|---|---|---|
| F1 | API Gateway para el frontend | `API_BASE` en el compose: los dos frontends pasan **todos** por el gateway. 29 rutas explícitas, sin `{proxy+}` |
| F2 | No usar proxy en backend | ningún `proxy_pass`; el navegador llama a la API, no a nginx |
| F3 | CORS con la ruta de la API | el gateway reenvía `Origin` y devuelve la respuesta CORS del servicio, que valida contra su lista explícita |
| F4 | Todas las colas funcionan | `evidencias/55` (RabbitMQ) + `evidencias/51` (DLT de Kafka) |

## Cosas que hay que saber para no romperlo

1. **La Elastic IP no cambia** al apagar y prender. Cuesta ~US$0,12/día mientras
   la instancia está apagada, y a cambio no hay que reapuntar nada.
2. **`API_BASE` y `HOST_PUBLICO`** viven en el `.env` de la instancia (no en git).
   `HOST_PUBLICO` decide CORS, el `returnUrl` de Webpay y el WebSocket;
   `API_BASE` decide por dónde entran los frontends. Vaciar `API_BASE` devuelve
   el sistema al modo desarrollo (un puerto por servicio) sin tocar código.
3. **Dos cosas NO pasan por el gateway, a propósito**: el WebSocket de métricas y
   el stream SSE de notificaciones. Una integración HTTP de API Gateway REST
   bufferea la respuesta, así que un stream no la atraviesa. Van directos.
4. **Una ruta recién publicada da 403 o 500 durante ~1 minuto**: es propagación
   del stage, no un error de configuración. Esperar y reintentar.
5. **El AWS CLI de Windows escribe CRLF.** Un id capturado del CLI con `\r` al
   final rompe la firma SigV4 y AWS responde
   `UnrecognizedClientException: security token invalid`, que no tiene nada que
   ver con las credenciales. Los scripts filtran con `tr -d '\r'`.
6. Los puertos **8083–8089 están abiertos a `0.0.0.0/0`** porque el API Gateway
   no tiene IPs fijas y necesita alcanzarlos. Es una limitación conocida que
   cerraría P4 (Cognito), fuera de alcance. 8090, 8091, 15672 y los brokers
   siguen restringidos a la IP del equipo: **el viernes, desde otra red, hay que
   agregar esa IP al security group** o no cargarán los frontends.

## Qué falta

| | Qué |
|---|---|
| **P1** | Consola alineada al backend nuevo (Diego, en curso) |
| **P6** | Evidencia de un pago Webpay **aprobado** — 10 min, en `http://18.211.30.140:8091` |
| **P7** | Ensayo de la demo y evidencias de fallos (los dos) |
| — | Corregir `evidencias/50`: afirma que 12 rutas `/v1` dan 200 y cinco dan **500** (nunca tuvieron *integration response*). O se corren el `13` y se republica, o se corrige el documento |
| — | Abrir el PR de `p2b-gateway-frontend` hacia `1samadhi/cloud-ev3-kafka` |

## Cómo retomar

```bash
# 1. Start Lab y pegar el bloque de AWS Details en ~/.aws/credentials
#    (sólo [default] y las tres aws_*; si se cuelan AWSAccountId/Region, falla)
# 2. Prender la instancia (la IP NO cambia)
aws ec2 start-instances --region us-east-1 --instance-ids i-0f5f47b9ffdefa327
# 3. Comprobar que el sistema volvió solo (restart: unless-stopped)
BASE_HOST=18.211.30.140 python infra/prueba-humo.py     # debe dar TODO OK
# 4. Al terminar, apagar: es lo que consume crédito
aws ec2 stop-instances --region us-east-1 --instance-ids i-0f5f47b9ffdefa327
```
