# Build stage
FROM maven:3.9-eclipse-temurin-21 as builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre
RUN useradd -m -u 1000 appuser
WORKDIR /app
COPY --from=builder /build/target/ms-pago-*.jar app.jar
RUN chown appuser:appuser /app && chmod 755 /app
USER appuser
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
