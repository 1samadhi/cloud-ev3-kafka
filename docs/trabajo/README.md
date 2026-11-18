# Bitácora de trabajo — Pedidos360

Un documento por tarea, para que quien siga no tenga que reconstruir el *por qué*
leyendo diffs.

El trabajo se reparte en dos repositorios. Los números son los mismos en los dos
índices, así que el 05 y el 06 son dos mitades de la misma tarea.

## En este repositorio (`cloud-ev3-kafka`, rama `fix-callback-webpay`)

| # | Tarea | Documento | Commit |
|---|---|---|---|
| 02 | El pago se confirma y vuelve al frontend | [02-callback-webpay.md](02-callback-webpay.md) | `ce0b72f` |
| 05 | Actuator en los tres servicios y compose raíz | [05-actuator-y-compose.md](05-actuator-y-compose.md) | `5bd9faf` |
| 07 | Las dos Lambdas y su infraestructura | [07-lambdas-telemetria.md](07-lambdas-telemetria.md) | `2f1d402` |

## En el repositorio del frontend (`cloud-ev3-front`, rama `rediseno-react`)

| # | Tarea | Documento | Commit |
|---|---|---|---|
| 01 | Rediseño de la consola a React + TypeScript | `docs/trabajo/01-rediseno-consola-react.md` | `a710fb1` |
| 03 | Espera creciente en la lectura de Operaciones | `docs/trabajo/03-espera-creciente-operaciones.md` | `bb5bc5b` |
| 04 | Tests con Vitest | `docs/trabajo/04-tests-vitest.md` | `0de2a16` |
| 06 | El sondeo de salud pasa a `/actuator/health` | `docs/trabajo/06-sondeo-de-salud.md` | `c863e44` |
| 08 | Reconexión automática del WebSocket | `docs/trabajo/08-reconexion-websocket.md` | `ca4ea79` |

## Lo que conviene saber antes de tocar algo

- **Lo que ya estaba bien, está bien.** `ms-kafka-admin` valida el factor de
  replicación contra los brokers vivos, protege `payment-attempts` e
  `interacciones-user` y excluye los tópicos internos `__`, todo del lado
  servidor. Lo que el README promete, lo cumple.
- **Los dos repos van de la mano.** El compose de la raíz construye la imagen del
  frontend desde `../cloud-ev3-front`. Clonarlos uno al lado del otro.
- **Nada de esto se ejecutó en infraestructura real.** En el entorno donde se
  trabajó no había Docker ni credenciales de AWS. Cada documento dice qué se
  verificó y qué no; en resumen: el código Java compila, los tests pasan,
  `terraform validate` da Success, y **ni `docker compose up` ni
  `terraform apply` se corrieron**.
- **Maven y JDK 25.** El proyecto apunta a Java 21 y la máquina de trabajo tenía
  JDK 25, donde el Lombok que trae Spring Boot 3.5.6 no procesa anotaciones. Para
  compilar ahí hace falta `-Dlombok.version=1.18.44`. No es un problema del
  código.

## Pendientes conocidos

| Pendiente | Dónde está explicado |
|---|---|
| Ningún microservicio escribe en `EventosOperaciones`: la telemetría se alimenta a mano | 07 |
| El código de comercio de Transbank está duplicado y escrito en el código | abajo |
| `ms-pago` y `ms-recomendaciones` comparten la base `pagos_db` | abajo |
| `evidencias/` quedó desactualizada respecto de los endpoints y el CORS actuales | abajo |

### Transbank en el código

`597055555532` aparece literal en `PagosController.java` **y** en
`PagosServices.java`. Además el README afirma que las credenciales no van en el
código, lo que es cierto para la base de datos y falso para Transbank.
Corresponde moverlo a `app.transbank.*` leído por `@Value`, en un solo lugar.

### Base de datos compartida

Los dos servicios que persisten apuntan a `mysql-db:3306/pagos_db`. Es el
antipatrón de base compartida entre microservicios y es una pregunta probable en
la defensa. O se separan los schemas, o se tiene la respuesta preparada.
