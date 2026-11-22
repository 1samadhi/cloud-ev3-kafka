package com.pedidos360.productos.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuración centralizada de CORS (misma forma que ms-pago).
 *
 * Los orígenes vienen de una propiedad sobrescribible por variable de entorno para no
 * recompilar al cambiar de entorno. Se permite explícitamente X-Usuario-Id porque es el
 * header con el que la tienda identifica al usuario mientras no exista Cognito; si no
 * estuviera en la lista, el preflight del navegador lo bloquearía.
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
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Usuario-Id")
                .allowCredentials(true)
                .maxAge(3600);

        // La consola sondea /actuator/health para pintar el estado del servicio; sin este
        // mapeo el navegador bloquea la respuesta y lo mostraría caído estando arriba.
        registry.addMapping("/actuator/**")
                .allowedOrigins(origenes)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
