# Build stage
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre
# curl va solo en la imagen de runtime y solo para el HEALTHCHECK: es lo que
# permite que el depends_on del compose espere a que el servicio este REALMENTE
# listo, y no apenas a que el contenedor arranque.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd -m -u 1000 appuser
WORKDIR /app
COPY --from=builder /build/target/ms-notificaciones-*.jar app.jar
RUN chown appuser:appuser /app && chmod 755 /app
USER appuser
EXPOSE 8088

# start-period generoso: un Spring Boot con JPA y RabbitMQ tarda bastante en el
# primer arranque, y sin margen el contenedor se marcaria unhealthy antes de
# haber terminado de levantar.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://localhost:8088/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
