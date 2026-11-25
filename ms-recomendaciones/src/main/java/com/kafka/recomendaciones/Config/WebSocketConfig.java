package com.kafka.recomendaciones.Config;

import com.kafka.recomendaciones.Component.DifusorMetricasWebSocket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket nativo en /ws/metricas, sin STOMP ni SockJS.
 *
 * Por que nativo: el dashboard solo recibe (el servidor empuja un snapshot cada
 * 2 s); no hay suscripciones a destinos ni mensajes del cliente que rutear, que
 * es lo que STOMP agrega. Asi la consola usa el WebSocket del navegador tal cual,
 * el mismo hook que ya usa para API Gateway.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DifusorMetricasWebSocket difusor;
    private final String[] origenes;

    public WebSocketConfig(DifusorMetricasWebSocket difusor,
                           @Value("${app.cors.origenes-permitidos:http://localhost:5173,http://localhost:5174,http://localhost:8090,http://localhost:8091}")
                           String origenesCsv) {
        this.difusor = difusor;
        this.origenes = CorsConfig.separar(origenesCsv);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Mismos origenes que CORS: el handshake rechaza (403) a cualquier pagina
        // ajena que intente leer las metricas desde el navegador de un operador.
        registry.addHandler(difusor, "/ws/metricas").setAllowedOrigins(origenes);
    }
}
