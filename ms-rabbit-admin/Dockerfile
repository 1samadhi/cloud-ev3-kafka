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
# UID 10001 y no 1000: eclipse-temurin:21-jre viene sobre Ubuntu, que ya trae
# el usuario 'ubuntu' con UID 1000, y useradd -u 1000 falla con codigo 4.
RUN useradd -m -u 10001 appuser
WORKDIR /app
COPY --from=builder /build/target/ms-rabbit-admin-*.jar app.jar
RUN chown appuser:appuser /app && chmod 755 /app
USER appuser
EXPOSE 8089

# start-period generoso: el primer arranque de Spring Boot en un contenedor con
# memoria limitada tarda, y sin margen se marcaria unhealthy antes de terminar.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8089/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
