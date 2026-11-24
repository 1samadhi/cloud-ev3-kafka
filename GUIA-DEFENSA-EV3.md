# Guía de defensa — EV3 Pedidos360

Para estudiar antes de presentar. Las respuestas están en el orden en que es
probable que las preguntas aparezcan, y cada una dice **dónde mirar** si querés
el detalle.

> Regla de oro: si no sabés algo, decí "eso no lo implementé" o "eso lo hizo mi
> compañero y no lo revisé a fondo". Inventar una respuesta es peor que no
> tenerla, porque la siguiente pregunta te deja sin piso.

---

## Nivel 1 · Las que caen casi seguro

### «¿Por qué usás Kafka y RabbitMQ al mismo tiempo? ¿No es redundante?»

**La respuesta corta:** porque resuelven problemas distintos.

Kafka es un **log**: el mensaje queda y varios consumidores lo leen por separado,
cada uno a su ritmo. Una interacción del usuario la leen dos consumidores que la
interpretan distinto — uno recalcula recomendaciones, el otro actualiza las
métricas en vivo — y cada uno tiene su propio grupo para no robarse mensajes.

RabbitMQ es una **cola**: el mensaje se entrega y se consume. Una notificación es
para un cliente específico; no tiene sentido que quede guardada para que otros la
relean.

**Si te aprietan:** «¿y no podrías hacer todo con Kafka?». Sí, técnicamente. Pero
las notificaciones necesitan patrones de enrutamiento por tipo
(`pedido.*`, `carrito.*`) y descarte de lo que no se puede entregar, que es
justamente para lo que RabbitMQ tiene exchanges y DLQ. Usar la herramienta que
calza es más defendible que forzar una sola.

---

### «¿Por qué siete microservicios? El enunciado pedía tres.»

**Sé honesto:** el enunciado pide pagos, recomendaciones y uno libre. Los tres
están: `ms-pago`, `ms-recomendaciones` y los dos de administración
(`ms-kafka-admin`, `ms-rabbit-admin`) como el libre.

Los otros tres —productos, carrito, notificaciones— salieron de que el dominio los
necesitaba: alguien tiene que tener el catálogo y reservar stock, alguien tiene que
armar el pedido, y las notificaciones son el consumidor natural de RabbitMQ.

**No digas** "para tener más". Di: "el flujo de compra no cerraba sin ellos".

---

### «Mostrame que el pago no guarda la tarjeta.»

Dos cosas que podés mostrar en vivo:

1. **La respuesta del pago** sólo trae los últimos cuatro:
   ```json
   {"estado":"AUTORIZADO","idOrden":"P-2","ultimos4":"6311"}
   ```
2. **La tarjeta se guarda como huella**, un SHA-256 con pepper:
   ```java
   return sha256(digitos + pepper);
   ```
   El pepper no está en el código, viene por variable de entorno.

**Dónde:** `ms-pago/.../Service/HuellaTarjeta.java`

---

### «¿Cómo funciona el antifraude?»

La regla del enunciado es *"misma tarjeta, más de 3 veces en 10 segundos"*.

```java
int total = (int) previosEnVentana + 1;
return new Decision(total, total > umbral);
```

**El detalle que vale la pena contar:** es `total > umbral`, no `>=`. Con umbral 3,
el tercer intento **todavía pasa** —alguien que se equivoca dos veces al tipear— y
el cuarto bloquea. En AWS da `[200, 200, 200, 429]`.

La clase no usa Spring, ni base de datos, ni el reloj del sistema: recibe los
instantes y decide. Por eso se prueba con JUnit plano.

**Dónde:** `ms-pago/.../Service/VentanaAntifraude.java`

---

## Nivel 2 · Las de la rúbrica

### «¿Qué pasa si llega un mensaje corrupto a Kafka?»

Tres piezas en cadena:

```
ErrorHandlingDeserializer → DefaultErrorHandler → DeadLetterPublishingRecoverer
```

Y la política **distingue qué vale reintentar**:

| Fallo | Qué hace | Por qué |
|---|---|---|
| base caída, otro ms no responde | reintentos exponenciales | puede recuperarse |
| JSON roto, validación que falla | al DLT de inmediato | reintentarlo mil veces no lo arregla |

**Lo podés demostrar en vivo.** Publicá basura a mano:

```bash
echo "{esto no es json valido" | docker exec -i kafka1 \
  kafka-console-producer --bootstrap-server localhost:9092 --topic pagos-resultado
```

Y en el log aparece:
```
Mensaje enviado a pagos-resultado.DLT: partición=2 offset=2 causa=JsonParseException
```

**Evidencia:** `evidencias/51-dlt-*.md`

---

### «¿Por qué no usaste `{proxy+}` en el API Gateway?»

Porque fue una de las cuatro observaciones de la EV2. Con proxy, el gateway es un
túnel y no sabe qué rutas existe; declarando cada una, el gateway **es** el
contrato de la API.

**El costo, y conviene mencionarlo** porque demuestra que entendiste: sin proxy
nada viaja solo. Los parámetros de consulta y las cabeceras hay que mapearlos a
mano, ruta por ruta:

```
integration.request.querystring.userId = method.request.querystring.userId
```

Si no lo hacés, `?userId=` nunca llega al backend y el servicio responde 400.
Me pasó, y así lo arreglé.

---

### «¿Las colas funcionan todas?»

Sí, y es verificable:

```
Kafka      interacciones-user · catalogo-productos
           payment-attempts · pagos-resultado      + los 4 .DLT
RabbitMQ   notificaciones.cliente · notificaciones.auditoria
           notificaciones.cliente.dlq
```

La prueba de humo recorre las nueve etapas del flujo y da **25/25**.

---

### «¿Dónde está el CORS?»

En dos lugares, y el segundo es el que casi nadie muestra:

1. **En el API Gateway**: cada ruta devuelve `Access-Control-Allow-Origin` y tiene
   un `OPTIONS` para el preflight.
2. **En el handshake del WebSocket**, que valida el origen con la misma lista:
   ```
   Origin: http://44.208.92.186:8090   → 101 Switching Protocols
   Origin: http://otro-origen.test     → 403
   ```

Ese 403 lo podés mostrar en vivo y es contundente: impide que una página ajena
abierta en el navegador del operador lea las métricas.

---

## Nivel 3 · Las incómodas

### «¿Esto lo hiciste vos?»

**La verdad, sin adornos:** el proyecto es en pareja con Diego Villota. Él armó la
integración de los siete microservicios y los dos frontends en React; yo hice el
despliegue en AWS, el API Gateway sin proxy, la persistencia en DynamoDB real, la
verificación del DLT y la corrección del panel de administración.

Tenés commits propios que lo respaldan, incluido el arreglo del P5 con sus siete
pruebas.

**Si preguntan por una parte que no tocaste:** "esa la hizo Diego, yo revisé que
funcionara pero no te puedo explicar el detalle de la implementación". Es una
respuesta profesional, no una excusa.

---

### «¿Por qué el DynamoDB es el real y no el emulador?»

Porque el emulador no demuestra nada de la nube. El servicio escribe en el
DynamoDB de la cuenta, y hay un detalle que conviene contar:

**El contenedor no lleva credenciales.** Usa el rol de la instancia:

```
arn:aws:sts::471112736485:assumed-role/LabRole/i-0bdac4ec3cb93f53c
```

Las credenciales del Learner Lab vencen en una hora; si las pegaba en el
contenedor, el servicio se moría a mitad de la demo. Con el rol, el SDK las pide
al metadata de EC2 y se renuevan solas.

**Lo que costó:** el metadata necesita `HttpPutResponseHopLimit` en 2 para ser
alcanzable desde dentro de un contenedor. Por defecto es 1 y el contenedor no
llega.

---

### «¿Qué te faltó?»

Decilo antes de que lo encuentren:

| | Estado |
|---|---|
| Webpay real | el código está, faltan credenciales de Transbank |
| Cognito + JWT | no implementado |
| Lambda desde el stream | la tabla tiene stream `NEW_IMAGE`, la Lambda no está desplegada |

Que la tabla ya tenga el stream activado muestra que el camino está preparado, no
improvisado.

---

## Chuleta de números

| Dato | Valor |
|---|---|
| Microservicios | 7 (Java 21 · Spring Boot 3.5.6) |
| Contenedores | 21 |
| Tópicos Kafka | 4 + 4 DLT |
| Brokers / nodos Rabbit | 3 y 3 |
| Rutas del gateway | 22, sin proxy |
| Tablas DynamoDB | 3 |
| Prueba de humo | 25/25 |
| Pruebas de ms-carrito | 81 |
| Instancia | t3.large · 5,4 GB usados de 7,8 |
| IP | 44.208.92.186 (fija) |

## Pesos del motor de recomendaciones

Por si preguntan cómo se arma el "Para ti":

```
ver producto 1.0 · cupón 0.5 · envío 0.0 · agregar al carrito 3.0 · comprar 5.0
```

Y el puntaje combina categoría (3.0), subcategoría (2.0), marca (1.0) y
popularidad (0.5). De ahí sale que pueda decir *"porque compraste WH-1000XM5"*.

---

## Orden sugerido para la demo

1. **La tienda** (`:8091`) — navegá, agregá al carrito, comprá.
2. **Las recomendaciones cambian** — mostrá que el motivo pasa de "porque viste" a
   "porque compraste".
3. **La consola** (`:8090`) — métricas moviéndose en vivo por WebSocket.
4. **El antifraude** — cuatro pagos con la misma tarjeta, el cuarto da 429.
5. **El DLT** — publicá basura a mano y mostrá el log con la causa.
6. **Kafka UI** (`:8080`) — los tópicos, los grupos y su lag.
7. **La consola de AWS** — API Gateway con las 22 rutas y las tablas de DynamoDB.

Dejá el DLT y el antifraude para el final: son lo que menos gente muestra y lo que
más suma.
