# ms-pago (8083)

Pagos con Webpay Plus (integración Transbank) o pasarela SIMULADA, y antifraude por huella
de tarjeta sobre Kafka. Contrato: `../PLAN-INTEGRACION-EV3.md` §2, §3 y §4.

## Regla antifraude

"Misma tarjeta, **más de 3** veces en 10 s" → el **4.º** intento dentro de la ventana bloquea la
huella 30 min (`app.antifraude.*`: `ventana-segundos`, `umbral`, `bloqueo-minutos`).

- Ventana deslizante real: tabla `intento_tarjeta`; por cada intento se cuentan los de esa
  huella con `instante > t − 10 s`, **incluido el actual** (`VentanaAntifraude`, JUnit puro).
- Al infringir: fila en `bloqueo_tarjeta`, fila en `alerta_fraude` y log `ERROR ALERTA FRAUDE`.
- `@Scheduled` borra cada 5 min los intentos con más de 1 h.
- El consumidor `grupo-antifraude` (3 hilos, uno por partición) es el que cuenta, bloquea y
  alerta. Como decide de forma asíncrona, el HTTP **anticipa** su decisión leyendo los intentos
  que el consumidor ya registró (`ServicioAntifraude.anticiparDecision`): el 4.º intento recibe
  `429` y no se cobra, pero se publica igual, porque es el que el consumidor necesita para
  registrar el bloqueo y la alerta. Consistencia eventual de milisegundos: dos intentos que
  llegan a la vez en el mismo instante pueden pasar ambos; el siguiente ya ve el bloqueo.

### Huella

`SHA-256(dígitos de la tarjeta + ANTIFRAUDE_PEPPER)` en hex. El número **nunca** se guarda, se
publica ni se loguea: sólo viajan la huella y `ultimos4`.

**Con WEBPAY la huella es de la cuenta** (`SHA-256("cuenta:" + clientId + pepper)`): Transbank
captura la tarjeta en su propio formulario y no la entrega antes del pago (diseño PCI DSS), así
que al iniciar no hay número que hashear. La ventana y el umbral son los mismos; cambia la clave.
La regla por tarjeta real se demuestra con la pasarela `SIMULADA`.

## API

| Método y ruta | Respuesta |
|---|---|
| `POST /api/pagos/iniciar` | ver abajo |
| `POST/GET /api/pagos/commit` | 302 a `<front>/callback?estado=AUTHORIZED\|REJECTED\|ANULADO\|ERROR&orden=…` |
| `GET /api/alertas` | `{ bloqueosActivos, totalClientesVigilados, alertas:[{ idCliente, cantidadIntentos, bloqueadoHasta, minutosRestantes, actualizadoEn, huellaTarjeta, ultimos4 }] }` |

`POST /api/pagos/iniciar` — cuerpo `{ clientId, amount, transactionId, pasarela?, tarjeta?: { numero }, origen? }`
(`pasarela` WEBPAY por defecto; `tarjeta.numero` 13–19 dígitos, obligatorio con SIMULADA;
`origen` TIENDA | CONSOLA, CONSOLA por defecto).

| Caso | HTTP | Cuerpo |
|---|---|---|
| WEBPAY | 200 | `{ url, token }` |
| SIMULADA aprobada | 200 | `{ estado: "AUTORIZADO", idOrden, ultimos4 }` |
| SIMULADA rechazada (tarjeta termina en `0002`) | 402 | `{ estado: "RECHAZADO", idOrden, ultimos4 }` |
| Validación / JSON ilegible / enum desconocido | 400 | `{ error, detalle, timestamp }` (nunca repite la tarjeta) |
| Huella bloqueada | 429 | `{ message, bloqueadoHasta, intentos, minutosRestantes, error, timestamp }` |
| Transbank caído | 502 | `{ error, detalle, timestamp }` |
| Kafka no confirma el intento en 5 s | 503 | `{ error, detalle, timestamp }` |

El redirect del commit sale de una lista cerrada (`FRONT_URL_TIENDA` / `FRONT_URL`) según
`origen`; un valor desconocido cae en la consola. Nunca se redirige a una URL del cliente.

## Kafka

| Tópico | Rol | Key |
|---|---|---|
| `payment-attempts` | produce (HTTP) y consume (`grupo-antifraude`, concurrency 3) | huella |
| `payment-attempts.DLT` | `DeadLetterPublishingRecoverer` (misma partición) + `ConsumidorDlt` (`grupo-dlt-ms-pago`) | la original |
| `pagos-resultado` | produce `ResultadoPagoDTO` (SIMULADA y commit/anulación Webpay) | idOrden |

JSON roto o DTO inválido → directo al DLT sin reintentos; errores de base → 3 reintentos
(1 s, ×2, máx. 10 s) y luego DLT.

## Pruebas

```bash
cmd.exe /c 'C:\Users\diego\.tools\apache-maven-3.9.11\bin\mvn.cmd -B test'
```

Sin Docker: `@EmbeddedKafka` (KRaft) + H2 + `@WebMvcTest`.
