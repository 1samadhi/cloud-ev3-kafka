# 07 · Las dos Lambdas del dashboard y su infraestructura

Commit `2f1d402`. La reconexión del cliente está en
`docs/trabajo/08-reconexion-websocket.md` del otro repositorio.

## El hueco

La pantalla de Operaciones consumía un endpoint `wss://` que **no existía en
ningún repositorio**. El pipeline de tiempo real estaba descrito con detalle en
`ARQUITECTURA-PEDIDOS360.md` —dos Lambdas, DynamoDB Streams como disparador,
tabla `ConexionesWebSocket`, `NEW_IMAGE`, batch 50, ventana de 1 s, `LATEST`—
pero no había una línea de código ni de infraestructura.

**Se implementó el diseño que ya estaba documentado**, incluidos esos números, en
lugar de inventar una variante.

```
escritura en DynamoDB → Streams → Lambda Procesador → API Gateway → navegador
                                        ↑
   $connect / $disconnect → Lambda Conexiones → tabla ConexionesWebSocket
```

## `lambda-telemetria/` — un jar, dos handlers

| Función | Handler | Qué hace |
|---|---|---|
| Conexiones | `ConexionesHandler` | registra y borra conexiones en `$connect` / `$disconnect` |
| Procesador | `ProcesadorHandler` | recibe el lote del stream y lo difunde a los conectados |

Un solo artefacto porque comparten el modelo del evento y la mitad de las
dependencias: separarlos duplicaría 11 MB sin ganar nada.

### Decisiones y sus motivos

| Decisión | Motivo |
|---|---|
| **Sin Spring Boot** (el pom no hereda del parent) | una Lambda no levanta un contexto; arrancar uno en cada invocación multiplicaría el arranque en frío, que es lo único delicado de Java sobre Lambda |
| Netty y Apache HttpClient **excluidos** del SDK | se usa `url-connection-client` sobre la JDK; el jar baja de 16 MB a 11 MB y en Lambda el tamaño se paga en cada arranque en frío |
| Los clientes del SDK se crean **fuera** del handler | las invocaciones reutilizan el contenedor; crearlos por llamada pagaría la inicialización en cada mensaje |
| Se difunde **un** mensaje con el lote completo | con la ventana de 1 s los eventos llegan agrupados y `postToConnection` se cobra por mensaje; el dashboard ya espera un arreglo |
| Un **410 Gone** borra la conexión | cuando un navegador se va sin que `$disconnect` alcance a limpiar, sin borrar ahí la tabla acumula conexiones muertas a las que se escribe para siempre |
| El endpoint de administración va en **`https://`** | el navegador usa `wss://` contra el mismo endpoint; son dos esquemas de la misma API y confundirlos es el error clásico del montaje |
| `ServicesResourceTransformer` en el shade | el SDK v2 se apoya en `META-INF/services`; sin fusionarlos el jar falla en runtime al resolver el cliente HTTP |
| Un 500 en `$connect` se deja propagar | API Gateway rechaza la conexión, que es lo correcto: mejor que el navegador se entere ahora que quedar conectado sin estar en la lista |

### Una trampa que costó una compilación

`aws-lambda-java-events` 3.x **ya no arrastra el SDK v1** y publica su propio
modelo en `com.amazonaws.services.lambda.runtime.events.models.dynamodb`.
Importar `com.amazonaws.services.dynamodbv2.model.AttributeValue` compila sólo si
alguien agrega esa dependencia vieja a mano.

En `ProcesadorHandler` **conviven los dos** `AttributeValue`: el del SDK v2 para
la tabla de conexiones, y el del modelo de eventos para la imagen del stream.
Está comentado en el import para que nadie los "unifique".

## `infra/terraform-telemetria/`

Diez archivos: dos tablas con TTL, el stream, las dos funciones, el event source
mapping, la API WebSocket con `$connect`/`$disconnect`/`$default`, dos roles IAM y
los grupos de log.

| Decisión | Motivo |
|---|---|
| `NEW_IMAGE` | al dashboard sólo le interesa el dato nuevo; `NEW_AND_OLD_IMAGES` duplicaría cada registro del stream sin que nadie lea la imagen vieja |
| `batch_size` 50 + ventana de 1 s | el lote es un techo, no una meta: lo que agrupa de verdad es la ventana, imperceptible en un tablero |
| `LATEST` | al desplegar no se reproduce todo el historial, que llenaría el tablero de eventos viejos |
| `maximum_retry_attempts = 2` y `maximum_record_age = 60` | un evento que no se puede difundir no debe bloquear el shard; sin límites el mismo lote se reintenta hasta expirar y detiene la telemetría |
| `PAY_PER_REQUEST` | la carga de una demostración es a ráfagas; con capacidad fija hay que elegir entre pagar de más o recibir throttling |
| TTL en las dos tablas | la telemetría se mira en vivo y después no sirve; las conexiones muertas se barren solas |
| **Dos** roles IAM | cada función recibe sólo lo que necesita y se ve en la consola quién puede qué |
| `execute-api:ManageConnections` acotado a la etapa | con `*`, cualquier Lambda de la cuenta podría escribir a cualquier WebSocket |
| `filebase64sha256` del jar en la función | sin el hash, Terraform no detecta que el código cambió y un apply después de recompilar no sube nada |
| Grupos de log **declarados** | creados por Lambda quedan con retención "para siempre" |
| Estado de Terraform en local | es un proyecto con un operador; con más gente habría que mover el backend a S3 con bloqueo |

## Despliegue

```bash
cd lambda-telemetria && mvn clean package
cd ../infra/terraform-telemetria
terraform init && terraform apply
```

La salida `url_websocket` va al campo *Endpoint WebSocket* de la consola, o como
`WS_URL` al contenedor del frontend. Para probar el pipeline con la consola
conectada:

```bash
aws dynamodb put-item --table-name EventosOperaciones-desarrollo \
  --item file://evento-prueba.json --region us-east-1
```

## Verificación

| Qué | Resultado |
|---|---|
| `mvn clean package` | BUILD SUCCESS, jar de 11 MB, 0 clases de Netty |
| 8 tests JUnit de `EventoOperaciones` | verde |
| `terraform validate` | Success |
| `terraform fmt -check` | limpio |

Los tests cubren la conversión de items de DynamoDB a JSON, que es **la única
parte con lógica propia**: el resto es llamar al SDK. Fijan que un entero no
llegue como `42.0`, que el desenvolvimiento sea recursivo (las alertas llevan
listas de mapas), y que un número mal formado no tumbe el lote completo.

**`terraform apply` NO se ejecutó**: no hay credenciales de AWS en el entorno de
trabajo. La infraestructura está validada sintáctica y semánticamente, no
desplegada. Tampoco se invocó ninguna Lambda.

## El pendiente importante

Las Lambdas difunden lo que haya en `EventosOperaciones`, pero **ningún
microservicio escribe ahí todavía**. Hoy la tabla se alimenta a mano con el
`put-item` de prueba.

Para cerrar el pipeline hay que escribir el evento en DynamoDB además de
publicarlo en Kafka, en dos puntos concretos:

- `ms-recomendaciones` → `InteraccionService`, después de producir la interacción;
- `ms-pago` → `ConsumidorComponent`, cuando el antifraude marca un bloqueo.

Requiere credenciales de AWS en los servicios, que es la razón por la que no se
hizo junto con esto.
