# Pedidos360 — Arquitectura de la Evaluación 3

> Qué hace cada componente **en el dominio del e-commerce**, y por qué está ahí.
> Este documento es la base del guion de la presentación.

## El negocio en una frase

Pedidos360 es una tienda en línea que creció. Necesita tres cosas: **cobrar de forma segura**,
**entender qué miran sus clientes** para recomendarles productos, y que el equipo de operaciones
**vea lo que pasa en el momento en que pasa**, no al día siguiente en un reporte.

Todo lo demás de esta arquitectura existe para resolver esas tres cosas.

---

## Mapa completo

```
   CLIENTE en la tienda
        │
        ├── navega productos ─────────────┐
        │   (ver, agregar al carrito)     │
        │                                 ▼
        │                        ┌──────────────────────┐
        │                        │ ms-recomendaciones   │ :8084
        │                        │ publica interaccion  │
        │                        └──────────┬───────────┘
        │                                   │
        │                        ╔══════════▼═══════════╗
        │                        ║  interacciones-user  ║  3 part · 3 repl
        │                        ╚══════════╤═══════════╝
        │                                   │ grupo: recommendation-consumer-group
        │                                   ▼
        │                        ┌──────────────────────┐
        │                        │ consumidor           │
        │                        │ calcula Top 5        │──► MySQL  recomendaciones
        │                        └──────────┬───────────┘
        │                                   │
        └── paga el carrito ──────┐         │
                                  ▼         │
                        ┌──────────────────────┐
                        │ ms-pago              │ :8083
                        │ 1. ¿cliente bloqueado?│──► MySQL  estado_pago_cliente
                        │ 2. publica intento    │
                        │ 3. pide token Webpay  │──► Transbank
                        └──────────┬───────────┘
                                   │
                        ╔══════════▼═══════════╗
                        ║   payment-attempts   ║  3 part · 3 repl
                        ╚══════════╤═══════════╝
                                   │ grupo: payment-velocity-group
                                   ▼
                        ┌──────────────────────┐
                        │ consumidor antifraude│
                        │ 3 intentos en 10 s   │──► MySQL (bloqueo 30 min)
                        └──────────┬───────────┘
                                   │
            ambos consumidores ────┴────┐
            escriben el evento          │
                                        ▼
                              ┌───────────────────┐
                              │ DynamoDB          │
                              │ EventosPedidos360 │
                              └─────────┬─────────┘
                                        │ Streams (NEW_IMAGE)
                                        ▼
                              ┌───────────────────┐
                              │ Lambda Procesador │
                              │ lee el evento     │
                              └─────────┬─────────┘
                                        │ postToConnection
                                        ▼
                              ┌───────────────────┐
                              │ API Gateway       │
                              │ WebSocket         │
                              └─────────┬─────────┘
                                        │   ▲
                                        │   └── Lambda Conexiones
                                        │       registra $connect
                                        ▼       y borra $disconnect
                              ┌───────────────────┐
                              │ Dashboard         │
                              │ OPERACIONES       │
                              └───────────────────┘

   ms-kafka-admin :8085  ──► administra y supervisa todo el clúster
```

---

## Componente por componente

### Apache Kafka — el sistema nervioso de la tienda

**Qué hace en Pedidos360:** transporta todo lo que *ocurre* en la tienda. Cada clic en un
producto y cada intento de pago es un evento que entra a un tópico y queda disponible para
quien lo necesite.

**Los dos tópicos y por qué son esos:**

| Tópico | Qué lleva | Quién lo produce | Quién lo consume |
|---|---|---|---|
| `payment-attempts` | cada intento de pago de un cliente | el checkout | el antifraude |
| `interacciones-user` | cada vista, carrito o compra de un producto | el catálogo y el carrito | el motor de recomendaciones |

**Por qué Kafka y no una llamada HTTP directa entre servicios.** Es la pregunta que hay que
saber responder:

- **Desacople:** el checkout no espera a que el antifraude termine. Publica y devuelve el token
  a Transbank. Si el antifraude está caído, el cliente igual compra, y el análisis se hace
  cuando el servicio vuelve: los eventos lo esperan en el tópico.
- **Picos de tráfico:** en un Cyber Monday el clickstream se multiplica. Kafka absorbe la ráfaga
  y los consumidores procesan a su ritmo. Con llamadas HTTP directas, el servicio lento tumba al
  rápido.
- **Varios consumidores del mismo evento:** hoy las interacciones alimentan las recomendaciones;
  mañana pueden alimentar también un sistema de inventario o un reporte, **sin tocar al
  productor**. Cada uno con su propio grupo de consumo y su propio avance.
- **Reprocesar:** con 7 días de retención, si se corrige la fórmula de recomendación se pueden
  volver a leer los eventos de la semana y recalcular. Una llamada HTTP se pierde para siempre.

**La clave del mensaje es el `clientId`.** Kafka garantiza orden sólo *dentro* de una partición.
Usando el cliente como clave, todos sus eventos caen en la misma partición y llegan ordenados.
Eso es lo que permite que la ventana de 10 segundos del antifraude cuente bien: si los intentos
llegaran desordenados, la detección fallaría.

**3 brokers y 3 réplicas:** cada partición tiene una copia completa en los tres brokers. Si uno
se cae, otro toma el liderazgo y no se pierde ningún evento. Con `min.insync.replicas=2` una
escritura se confirma sólo cuando al menos dos réplicas la tienen.

### ms-pago — el checkout

**Qué hace:** cobra. Recibe el carrito, verifica que el cliente no esté bloqueado, publica el
intento, pide el token a Transbank y redirige al cliente a pagar.

**El antifraude en términos de negocio:** si una misma cuenta intenta pagar más de 3 veces en
10 segundos, no es una persona. Una persona tarda al menos 15 segundos en llenar los datos de
una tarjeta. Esa ráfaga es un bot probando números de tarjeta robados, y el comercio es quien
paga el costo de esos contracargos. Por eso se bloquea preventivamente 30 minutos.

**Detalle de seguridad para la defensa:** la regla cuenta por **cliente**, no por número de
tarjeta, porque el número nunca llega a nuestro backend — lo captura Transbank en su propio
formulario. Es lo correcto: un comercio que no almacena tarjetas no puede filtrarlas.

**Por qué el análisis va por Kafka y no en la misma petición:** porque el cliente no debe
esperar. La verificación de bloqueo es instantánea (una consulta a la base); el análisis de la
ráfaga es asíncrono. El checkout responde rápido y el fraude se detecta en paralelo.

### ms-recomendaciones — la analítica

**Qué hace:** escucha qué miran los clientes y les arma un Top 5 de productos.

**Los pesos tienen sentido comercial:** mirar un producto vale 1, agregarlo al carrito vale 2,
comprarlo vale 3. Comprar demuestra más interés que mirar, y el ranking lo refleja.

**Por qué en tiempo real y no en un proceso nocturno:** un cliente que está navegando *ahora*
debería recibir recomendaciones de lo que acaba de ver, no de lo que vio ayer. La sesión de
compra dura minutos; un batch nocturno llega tarde.

### MySQL — el estado

**Qué guarda:** lo que necesita consultas transaccionales y relaciones. El estado de bloqueo de
cada cliente, el historial de interacciones y el Top 5 calculado.

**Por qué MySQL para esto:** el antifraude necesita leer y actualizar el contador de un cliente
de forma consistente, con transacciones. Es exactamente para lo que sirve una base relacional.

### DynamoDB — el puente al tiempo real

**Qué guarda en Pedidos360:** los eventos que el dashboard de operaciones necesita mostrar.
Una venta aprobada, una alerta de fraude, un producto visto.

**La pregunta obligada: ¿por qué DynamoDB si ya hay MySQL?** Por una razón concreta y es la
única que importa:

> **DynamoDB Streams.** DynamoDB puede avisar automáticamente cada vez que se escribe una fila.
> MySQL no tiene nada equivalente sin montar infraestructura extra.

Sin ese aviso, el dashboard tendría que preguntar "¿hay algo nuevo?" cada pocos segundos
(*polling*): costoso, con retardo, y el 95% de las veces la respuesta es "no". Con Streams, el
evento empuja la notificación en el momento en que ocurre.

El segundo motivo es la forma del dato: son eventos, se escriben una vez y no se actualizan,
no necesitan relaciones ni transacciones. Es la carga de trabajo para la que DynamoDB está hecho.

### DynamoDB Streams — el disparador

**Qué hace:** cada escritura en la tabla genera un registro de cambio, y ese registro invoca la
función Lambda. Es el eslabón que convierte una escritura de base de datos en una notificación.

**Configuración y por qué:** `NEW_IMAGE` (sólo interesa el dato nuevo, no el anterior),
`BatchSize` 50, ventana de 1 segundo, `StartingPosition LATEST`.

El tamaño de lote es un techo, no una meta: lo que de verdad agrupa es la ventana de 1 segundo,
imperceptible en un dashboard y suficiente para acotar la cantidad de invocaciones. `LATEST`
evita que al arrancar se reproduzca todo el historial.

### Lambda Procesador — el que empuja

**Qué hace:** recibe el lote de eventos nuevos, los convierte al formato que entiende el
dashboard, y los envía a todos los navegadores conectados.

**Por qué Lambda y no un servicio corriendo siempre:** sólo se ejecuta cuando hay eventos. De
madrugada, sin ventas, no cuesta nada. Un servicio con un hilo esperando cobraría igual las 24
horas.

### Lambda Conexiones — el portero

**Qué hace:** cuando un operador abre el dashboard, registra su conexión; cuando lo cierra, la
borra. Mantiene la lista de a quién hay que notificar.

**Por qué hace falta:** API Gateway no guarda la lista de conectados. Entrega un
`connectionId` en el momento de conectar y después hay que saber a quiénes escribirles. Esa
lista vive en una tabla aparte, `ConexionesWebSocket`.

### API Gateway WebSocket — el canal

**Qué hace:** mantiene la conexión abierta entre el servidor y el navegador del operador, en
las dos direcciones.

**Por qué WebSocket y no HTTP:** HTTP es pregunta-respuesta; el cliente tiene que preguntar.
Para un tablero que debe reaccionar al instante, preguntar cada segundo es desperdicio y aun así
llega tarde. WebSocket deja el canal abierto y el servidor **empuja** el dato cuando existe.

**Por qué API Gateway y no un WebSocket propio:** no hay servidor que mantener, escala solo y se
paga por mensaje. El detalle a tener en cuenta: cierra la conexión a los 10 minutos de
inactividad, así que el front necesita reconectar.

### El dashboard de operaciones

**Quién lo mira:** el equipo de operaciones de Pedidos360, no el cliente. El cliente ve el
catálogo; operaciones ve el pulso del negocio.

**Las tres métricas en vivo:**

| Métrica | De dónde sale | Para qué sirve |
|---|---|---|
| **Productos más vistos** | `interacciones-user` | detectar qué se está buscando ahora y mover stock o precio |
| **Interacciones por minuto** | `interacciones-user` | pulso de tráfico: una caída brusca puede ser una falla del sitio |
| **Alertas de fraude** | `payment-attempts` | ver un ataque mientras ocurre, no en el reporte del lunes |

Las dos primeras responden al ejemplo del enunciado (*volumen de clics, productos más vistos*);
la tercera conecta los dos microservicios obligatorios en una sola pantalla.

### ms-kafka-admin — la sala de máquinas

**Qué hace:** administra y supervisa el clúster sin entrar por consola. Crea y lista tópicos,
muestra particiones, réplicas y réplicas en sincronía, y expone el **desfase de los grupos de
consumo**.

**Por qué importa el desfase (lag):** es el indicador de salud del sistema. Si el lag crece, los
consumidores no alcanzan a procesar lo que entra: las recomendaciones se atrasan y el fraude se
detecta tarde. Es la métrica que avisa que hay que escalar antes de que se note en el negocio.

---

## El flujo completo, contado como un caso

**Un cliente llamado Ismael entra a Pedidos360.**

1. Mira una aspiradora robot. El front publica una interacción (`view`, peso 1) al tópico
   `interacciones-user`, con su `clientId` como clave.
2. El consumidor la recibe, la guarda y recalcula su Top 5. La aspiradora sube en su ranking.
3. También escribe el evento en DynamoDB. Streams lo detecta, invoca la Lambda, y en el
   dashboard de operaciones el contador de "productos más vistos" se mueve.
4. Ismael la agrega al carrito (`cart`, peso 2) y va a pagar.
5. El checkout verifica que no esté bloqueado, publica el intento en `payment-attempts` y le
   pide el token a Transbank. Ismael paga con su tarjeta en el formulario de Transbank.
6. Transbank lo devuelve a la tienda, el checkout confirma la transacción y la venta queda
   registrada.

**Ahora entra un bot con una tarjeta robada.**

7. Dispara 4 intentos de pago en 2 segundos, todos con la misma cuenta.
8. Los cuatro se publican en `payment-attempts`. Como la clave es el cliente, los cuatro caen en
   la misma partición y llegan **en orden**.
9. El consumidor de antifraude cuenta: al tercero dentro de la ventana de 10 segundos, marca la
   cuenta como bloqueada por 30 minutos.
10. El cuarto intento recibe **429 Too Many Requests** y no llega a Transbank.
11. La alerta se escribe en DynamoDB → Streams → Lambda → y aparece en el dashboard **mientras
    el ataque está ocurriendo**.

Ese punto 11 es el que justifica toda la arquitectura: sin el pipeline de tiempo real, el equipo
se enteraría del ataque revisando reportes al día siguiente.

---

## Por qué cada tecnología, en una tabla

Para responder rápido en la defensa:

| Componente | Alternativa descartada | Por qué |
|---|---|---|
| Kafka | llamadas HTTP entre servicios | desacople, absorbe picos, varios consumidores, permite reprocesar |
| 3 brokers | un solo broker | sin réplicas, un fallo pierde eventos |
| clave = cliente | sin clave (reparto circular) | sin orden por cliente, la ventana de 10 s cuenta mal |
| DynamoDB | sólo MySQL | MySQL no avisa de los cambios; Streams sí |
| Streams + Lambda | consultar cada pocos segundos | retardo y costo, con la respuesta casi siempre vacía |
| WebSocket | recargar la página | el servidor empuja en vez de que el cliente pregunte |
| API Gateway | WebSocket propio en un servidor | sin servidor que mantener, escala solo |
| Lambda | servicio siempre encendido | se ejecuta sólo cuando hay eventos |
| MySQL | DynamoDB para todo | el bloqueo del antifraude necesita transacciones |

## Lo que va en AWS y lo que va local

| Componente | Dónde | Por qué |
|---|---|---|
| Kafka, microservicios, MySQL | EC2 | se pueden desarrollar local, la presentación va en AWS |
| DynamoDB + Streams | AWS | no tiene equivalente local con Streams |
| Las dos Lambdas | AWS | servicio gestionado |
| API Gateway WebSocket | AWS | servicio gestionado |
| Dashboard | navegador | consume el WebSocket de AWS |
