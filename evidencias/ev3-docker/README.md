# Evidencias — primer arranque real del sistema completo (22-11-2026)

Salidas copiadas de la terminal durante la verificación descrita en
`docs/trabajo/17-compose-primer-arranque-real.md`, con el sistema levantado por
`docker compose up -d --build` (Docker Desktop 4.99, motor 29.9.3, WSL2, 8 GB asignados).
Para regenerarlas: levantar el sistema y repetir los comandos de cada sección.

## Capturas

| Archivo | Qué muestra |
|---|---|
| `docker-tienda-inicio.png` | tienda en :8091 con el catálogo de ms-productos (20 productos, 8 categorías) |
| `docker-tienda-detalle.png` | detalle de producto con «También te puede interesar» y el motivo de cada recomendación |
| `docker-tienda-checkout.png` | checkout con datos de despacho |
| `docker-tienda-pago.png` | paso de pago: cupón GAMER5000 (−$5.000) calculado por ms-carrito, elección Webpay / tarjeta de prueba |
| `docker-webpay-transbank.png` | formulario real de Webpay (integración) con el monto del pedido |
| `docker-tienda-callback-anulado.png` | vuelta desde Transbank tras anular: `ANULADO`, pedido en «Pago rechazado» |
| `docker-tienda-mis-pedidos.png` | historial del cliente leído de ms-carrito |
| `docker-tienda-admin.png` | panel admin con ventas, más vendidos y rotación desde los endpoints reales |
| `docker-consola-inicio.png` | consola :8090, pantalla Pagos con bloqueos vigentes |
| `docker-consola-operaciones-ws.png` | dashboard conectado a `ws://localhost:8084/ws/metricas` con métricas en vivo |
| `docker-consola-kafka.png` | administración de Kafka: 3 brokers, 8 tópicos, 8 grupos |
| `docker-consola-recomendaciones.png` | top del usuario desde ms-recomendaciones |
| `docker-consola-operaciones.png` | dashboard antes de conectar |

## `docker compose ps` (tras el `up`)

```
NAME                 STATE     HEALTH    STATUS
consola              running   starting  Up 28 seconds (health: starting)
crear-topicos        exited              Exited (0) About a minute ago
dynamodb-local       running             Up 2 minutes
kafka-ui             running             Up About a minute
kafka1               running   healthy   Up 2 minutes (healthy)
kafka2               running   healthy   Up 2 minutes (healthy)
kafka3               running   healthy   Up 2 minutes (healthy)
ms-carrito           running   healthy   Up 22 seconds (healthy)
ms-kafka-admin       running   healthy   Up About a minute (healthy)
ms-notificaciones    running   healthy   Up 2 minutes (healthy)
ms-pago              running   healthy   Up About a minute (healthy)
ms-productos         running   healthy   Up About a minute (healthy)
ms-rabbit-admin      running   healthy   Up 2 minutes (healthy)
ms-recomendaciones   running   healthy   Up About a minute (healthy)
mysql-pedidos360     running   healthy   Up 2 minutes (healthy)
rabbitmq1            running   healthy   Up 2 minutes (healthy)
rabbitmq2            running   healthy   Up 2 minutes (healthy)
rabbitmq3            running   healthy   Up 2 minutes (healthy)
tienda               running   starting  Up Less than a second (health: starting)
zookeeper1           running   healthy   Up 2 minutes (healthy)
zookeeper2           running   healthy   Up 2 minutes (healthy)
zookeeper3           running   healthy   Up 2 minutes (healthy)
```

## Tópicos — `kafka-topics --describe` (configuración por flujo)

```
catalogo-productos      3  RF 3  min.insync.replicas=2, cleanup.policy=compact, retention.ms=-1,
                                 min.compaction.lag.ms=60000, min.cleanable.dirty.ratio=0.1, segment.ms=3600000
payment-attempts        3  RF 3  min.insync.replicas=2, cleanup.policy=delete, retention.ms=2592000000
pagos-resultado         3  RF 3  min.insync.replicas=2, cleanup.policy=delete, retention.ms=7776000000
interacciones-user      6  RF 3  compression.type=lz4, min.insync.replicas=2, cleanup.policy=delete, retention.ms=604800000
payment-attempts.DLT    3  RF 3  cleanup.policy=delete, retention.ms=1209600000
pagos-resultado.DLT     3  RF 3  cleanup.policy=delete, retention.ms=1209600000
catalogo-productos.DLT  3  RF 3  cleanup.policy=delete, retention.ms=1209600000
interacciones-user.DLT  6  RF 3  cleanup.policy=delete, retention.ms=1209600000
```

(Todos con `segment.bytes=268435456` salvo el compactado, y `retention.bytes=1073741824`.)

## Consumer groups — `kafka-consumer-groups --list`

```
grupo-antifraude
grupo-carrito
grupo-catalogo-recomendaciones
grupo-dashboard
grupo-dlt-carrito
grupo-dlt-ms-pago
grupo-dlt-recomendaciones
grupo-recomendaciones
```

`GET :8085/api/consumer-groups/grupo-recomendaciones` → 3 miembros (concurrency 3 sobre 6 particiones).

## RabbitMQ — clúster, colas y bindings

```
nodos corriendo: ['rabbit@rabbitmq2', 'rabbit@rabbitmq3', 'rabbit@rabbitmq1']
particiones de red: {}

name                        type    messages  consumers
notificaciones.auditoria    quorum  0         1
notificaciones.cliente.dlq  quorum  0         1
notificaciones.cliente      quorum  0         1

pedidos360.notificaciones      notificaciones.auditoria    #
pedidos360.notificaciones      notificaciones.cliente      carrito.*
pedidos360.notificaciones      notificaciones.cliente      envio.*
pedidos360.notificaciones      notificaciones.cliente      pedido.*
pedidos360.notificaciones.dlx  notificaciones.cliente.dlq
```

### Resiliencia: consumidor caído

Con `docker stop ms-notificaciones` se corrió la prueba de humo: la compra quedó **PAGADA** y
sólo falló el paso de notificaciones. La cola retuvo los mensajes y los entregó al volver:

```
=== cola mientras el consumidor está caído ===
notificaciones.cliente.dlq   0   0
notificaciones.cliente       3   0
=== cola después de docker start ms-notificaciones ===
notificaciones.cliente.dlq   0   1
notificaciones.cliente       0   1
```

## Prueba de humo — `python infra/prueba-humo.py`

```
1. Catálogo (ms-productos)
  [OK] lista de productos — 20 productos
  [OK] 8 categorías — ['celulares', 'laptops', 'audio', 'gaming', 'tv-video', 'smart-home', 'accesorios', 'camaras']
2. Navegación → interacciones-user (VER_PRODUCTO desde ms-productos)
  [OK] detalle item_107
  [OK] detalle item_108
  [OK] detalle item_107
3. Recomendaciones (consumo de Kafka en ms-recomendaciones)
  [OK] recomendaciones personalizadas tras navegar — AirPods Pro 2 (Porque viste WH-1000XM5); WH-1000XM5 (Te interesa Audio (100 %)); Flip 6 (Te interesa Audio (100 %))
4. Carrito (ms-carrito → Kafka + RabbitMQ)
  [OK] agregar WH-1000XM5 — HTTP 200
  [OK] cupón BIENVENIDA10 — descuento=29999
  [OK] cupón inexistente rechazado — HTTP 404
  [OK] datos de envío — total=269991
5. Pedido + pago SIMULADO (ms-carrito → ms-productos → ms-pago → pagos-resultado)
  [OK] pedido creado en PENDIENTE_PAGO — HTTP 201 P-1
  [OK] stock reservado — 18 → 17
  [OK] pago simulado aprobado — HTTP 200 {'estado': 'AUTORIZADO', 'idOrden': 'P-1', 'ultimos4': '6623'}
  [OK] la respuesta no expone el número de tarjeta
  [OK] pagos-resultado llegó a ms-carrito (pedido PAGADO) — PAGADO
6. Notificaciones (RabbitMQ → ms-notificaciones)
  [OK] carrito, pedido creado y pedido pagado notificados — ['carrito.producto-agregado', 'pedido.creado', 'pedido.pagado']
7. Antifraude: la misma tarjeta, más de 3 veces en 10 s
  [OK] los 3 primeros pasan y el 4.º recibe 429 — [200, 200, 200, 429]
  [OK] alerta registrada con últimos 4 — bloqueos activos: 1
8. Métricas en vivo (grupo-dashboard)
  [OK] métricas con tráfico — {"tipo": "METRICAS", "interaccionesPorMinuto": 8, ...}
9. Administración
  [OK] kafka-admin lee configuración del tópico
  [OK] kafka-admin describe el grupo — 3 miembros
  [OK] rabbit-admin ve el clúster — {"nombreCluster": "pedidos360", "totalNodos": 3, "nodosCorriendo": 3, "hayQuorum": true, "hayParticiones": false, ...}
  [OK] resumen admin con la venta — ingresos=269991

TODO OK
```

Repetida dos veces seguidas con tarjeta distinta por corrida: `TODO OK` ambas.

## CORS de Actuator tras la corrección

```
:8083 … :8089   Access-Control-Allow-Origin: http://localhost:8091
origen ajeno (http://malicioso.com): 0 cabeceras CORS
```
