# Etapa de build: compila dentro de la imagen para que el artefacto no dependa
# del JDK de quien construye (localmente hay JDK 25; la imagen usa 21).
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -B

# Etapa de runtime: sólo el JRE y el jar.
FROM eclipse-temurin:21-jre
# curl va sólo para el HEALTHCHECK: permite que el depends_on del compose espere
# a que el servicio esté REALMENTE listo y no apenas a que el contenedor arranque.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd -m -u 1000 appuser
WORKDIR /app
COPY --from=builder /build/target/ms-carrito-*.jar app.jar
RUN chown appuser:appuser /app && chmod 755 /app
USER appuser
EXPOSE 8087

# start-period generoso: JPA + Kafka + RabbitMQ tardan en el primer arranque y
# sin margen el contenedor se marcaría unhealthy antes de terminar de levantar.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://localhost:8087/actuator/health || exit 1

# JAVA_TOOL_OPTIONS (límites de memoria) se fija en el compose, no aquí.
ENTRYPOINT ["java", "-jar", "app.jar"]
