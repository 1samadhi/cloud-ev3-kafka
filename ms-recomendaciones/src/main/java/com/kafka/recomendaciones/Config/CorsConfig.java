package com.kafka.recomendaciones.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * CORS centralizado y parametrizado por CORS_ORIGENES: consola (5173 dev / 8090
 * docker) y tienda (5174 / 8091). Es infraestructura, no logica de negocio.
 *
 * Los mismos origenes se reutilizan en WebSocketConfig para el handshake de
 * /ws/metricas: un WebSocket no pasa por CORS, valida el header Origin aparte.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] origenes;

    public CorsConfig(@Value("${app.cors.origenes-permitidos:http://localhost:5173,http://localhost:5174,http://localhost:8090,http://localhost:8091}")
                      String origenesCsv) {
        this.origenes = separar(origenesCsv);
    }

    /** Usado tambien por WebSocketConfig para no duplicar el parseo. */
    public static String[] separar(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(origenes)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                // Lista cerrada: X-Usuario-Id es la identidad mientras no exista
                // Cognito, y Authorization queda lista para la fase 4.
                .allowedHeaders("Authorization", "Content-Type", "X-Usuario-Id")
                .allowCredentials(true)
                .maxAge(3600);

        // La consola sondea /actuator/health para pintar el estado del servicio;
        // sin este mapeo lo veria caido estando arriba. Solo lectura.
        registry.addMapping("/actuator/**")
                .allowedOrigins(origenes)
                .allowedMethods("GET", "OPTIONS")
                .maxAge(3600);
    }
}
