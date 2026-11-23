# 05 · Actuator en los tres servicios y un compose que levanta todo

Commit `5bd9faf`. La mitad frontend está en
`docs/trabajo/06-sondeo-de-salud.md` del otro repositorio.

## Por qué las dos cosas en una tarea

Se apoyan entre sí: sin healthchecks reales, un compose con `depends_on` sólo
espera a que el **contenedor arranque**, no a que el servicio pueda trabajar. Y
sin un compose completo, los healthchecks no tienen quién los use.

## Actuator

Ningún `pom.xml` lo incluía. Los tres servicios ahora exponen
`/actuator/health`, y **sólo** ese endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
```

El resto —`env`, `beans`, `configprops`, `heapdump`— filtra configuración y
credenciales sin pedir autenticación, y en este proyecto no hay Spring Security
que los cubra. Dejarlos abiertos sería un agujero gratis.

Para qué sirve, concretamente:

- el `HEALTHCHECK` de cada imagen, que antes no existía (el contenedor se
  reportaba sano apenas arrancaba el proceso);
- el `depends_on: condition: service_healthy` del compose;
- el indicador de la consola, que dejó de sondear endpoints de negocio.

### Spring Boot no trae indicador de salud para Kafka

Este fue el hallazgo de la tarea. De fábrica, `health` mira la aplicación, el
disco y la base de datos. **No hay `KafkaHealthIndicator`.** En un proyecto de
streaming eso deja fuera justo la dependencia que importa.

Se agregó `KafkaClusterHealthIndicator` en `ms-kafka-admin`, que es el servicio
que ya tenía un `AdminClient` configurado.

Reporta **DOWN con menos de 2 brokers vivos**, no con cero:

> Con un solo broker el clúster no puede honrar el `min.insync.replicas=2` con el
> que se crearon los tópicos, y una escritura con `acks=all` empezaría a fallar.
> Reportar UP en ese estado sería mentirle al healthcheck.

Usa `get()` **con timeout**: un `get()` sin límite deja colgado el healthcheck en
lugar de fallar.

### CORS

El mapeo cubre ahora `/api/**` **y** `/actuator/**`. Sin el segundo, el navegador
bloquea la respuesta y la consola muestra los tres servicios caídos estando
arriba — el síntoma más confuso posible, porque `curl` los muestra sanos.

### Dockerfiles

Cada uno gana su `HEALTHCHECK` contra `/actuator/health`, con `curl` instalado
sólo en la etapa de runtime, y `start-period` de 90 s: un Spring Boot con JPA y
Kafka tarda bastante en el primer arranque y sin margen se marcaría unhealthy
antes de terminar de levantar.

## `docker-compose.yml` en la raíz

Antes el arranque era: `infra/docker-compose.yml`, después
`ms-pago/docker-compose.yml` sólo para MySQL, después tres `mvn package`, tres
`java -jar`, y el frontend aparte. Para demostrar el sistema hay que poder
levantarlo entero sin una secuencia que se pueda ejecutar mal.

```bash
cp .env.example .env
docker compose up -d --build
```

13 servicios, en orden, esperando salud en cada capa:

```
zookeeper1..3 → kafka1..3 → creación de tópicos → microservicios → consola
mysql ─────────────────────────────────────────┘
```

`infra/docker-compose.yml` **sigue sirviendo** para trabajar sólo el clúster.

### Decisiones y sus motivos

| Decisión | Motivo |
|---|---|
| Los tópicos los crea un contenedor de un solo uso | la rúbrica pide particiones, replicación, retención y `min.insync.replicas` explícitos; `auto.create.topics` los haría con los defaults del broker |
| `--if-not-exists` en la creación | `up` dos veces no falla, y los servicios pueden esperar su `service_completed_successfully` |
| Dentro de la red se usa `kafkaN:29092` | el `9092` del host **no existe** en la red de Docker; es el error que deja los servicios sin poder producir |
| `PAGOS_RETURN_URL`, `FRONT_URL` y las tres `API_*` van con `localhost` | las abre el navegador del usuario, no Docker; un nombre de servicio no se resuelve ahí |
| El servicio `consola` apunta a `../cloud-ev3-front` | el frontend es otro repositorio; si no está clonado al lado, se comenta ese servicio y el backend levanta igual |

## Verificación

- Los tres servicios **compilan** (`BUILD SUCCESS` cada uno), lo que confirma que
  el health indicator y Actuator están bien usados.
- `infra/validar-compose.py` comprueba sin Docker: que el YAML parsee, que las
  anclas se mezclen, que todo `depends_on` apunte a un servicio existente, que
  los microservicios usen el listener interno, que las URL de navegador sean
  `localhost`, y que los orígenes de CORS coincidan con el puerto publicado de la
  consola.
- El validador se probó inyectando 6 errores a propósito: los detecta todos.
- Los tres estados de salud se vieron en la consola contra el mock.

**`docker compose up` NO se ejecutó**: no había Docker en el entorno de trabajo.
El compose está validado estructuralmente, no corrido. El primer
`up --build` es territorio sin explorar; conviene mirar los logs de
`crear-topicos` primero, que es el único servicio con un comando largo.
