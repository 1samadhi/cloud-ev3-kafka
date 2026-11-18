package com.kafka.recomendaciones.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuración centralizada de CORS.
 * 
 * El CORS es configuración de infraestructura, no lógica de negocio.
 * Se centraliza aquí para mantener separadas las responsabilidades y permitir
 * parametrización sin recompilación. Los orígenes se leen de una propiedad
 * que puede sobreescribirse por variable de entorno en tiempo de ejecución.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.origenes-permitidos:http://localhost:8090}")
    private String origenesCsv;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origenes = origenesCsv.split(",");
        for (int i = 0; i < origenes.length; i++) {
            origenes[i] = origenes[i].trim();
        }

        registry.addMapping("/api/**")
                .allowedOrigins(origenes)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);

        // El frontend sondea /actuator/health para pintar el estado de cada
        // servicio. Sin este mapeo el navegador bloquea la respuesta por CORS y
        // la consola mostraria el servicio como caido estando arriba.
        // Solo GET: health se lee, no se modifica.
        registry.addMapping("/actuator/**")
                .allowedOrigins(origenes)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
