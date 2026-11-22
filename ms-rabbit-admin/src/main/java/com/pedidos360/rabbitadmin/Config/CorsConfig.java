package com.pedidos360.rabbitadmin.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuración centralizada de CORS (misma forma que ms-pago y ms-notificaciones).
 *
 * El CORS es configuración de infraestructura, no lógica de negocio. Los orígenes
 * se leen de una propiedad que puede sobreescribirse por variable de entorno.
 * DELETE es imprescindible: borrar colas, exchanges y bindings es la mitad de
 * lo que hace esta API.
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

        registry.addMapping("/api/**")
                .allowedOrigins(origenes)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Usuario-Id")
                .allowCredentials(true)
                .maxAge(3600);

        // La consola sondea /actuator/health para pintar el estado de cada
        // servicio; sin este mapeo el navegador bloquea la respuesta por CORS.
        registry.addMapping("/actuator/**")
                .allowedOrigins(origenes)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
