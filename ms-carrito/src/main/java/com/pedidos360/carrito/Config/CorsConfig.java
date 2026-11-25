package com.pedidos360.carrito.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS centralizado (igual al de ms-pago). Es infraestructura, no lógica de negocio: los orígenes
 * vienen de CORS_ORIGENES para cambiarlos sin recompilar.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.origenes-permitidos:http://localhost:5173,http://localhost:5174,http://localhost:8090,http://localhost:8091}")
    private String origenesCsv;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origenes = origenesCsv.split(",");
        for (int i = 0; i < origenes.length; i++) {
            origenes[i] = origenes[i].trim();
        }

        // PATCH es necesario para actualizar cantidades; X-Usuario-Id identifica al usuario hasta Cognito.
        registry.addMapping("/api/**")
                .allowedOrigins(origenes)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Usuario-Id")
                .allowCredentials(true)
                .maxAge(3600);

    }
}
