package com.kafka.admin.Config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Parámetros del servicio en application.yaml (app.kafka-admin.*), para no
 * dejar nombres de tópicos ni tiempos de espera literales en la lógica.
 */
@Data
@ConfigurationProperties(prefix = "app.kafka-admin")
public class KafkaAdminProperties {

    /**
     * Cuánto se espera cada respuesta del AdminClient. Un get() sin límite deja
     * el hilo HTTP colgado si un broker no contesta; con límite se responde 503.
     */
    private long timeoutMs = 10_000;

    /** Tópicos que la API no deja borrar (los del proyecto y sus DLT). */
    private List<String> topicosProtegidos = new ArrayList<>(List.of(
            "interacciones-user", "payment-attempts", "pagos-resultado", "catalogo-productos",
            "interacciones-user.DLT", "payment-attempts.DLT", "pagos-resultado.DLT", "catalogo-productos.DLT",
            "telemtria-camion"));
}
