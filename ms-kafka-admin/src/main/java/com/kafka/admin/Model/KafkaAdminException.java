package com.kafka.admin.Model;

/**
 * Excepción personalizada para errores de administración de Kafka.
 */
public class KafkaAdminException extends RuntimeException {
    public KafkaAdminException(String message) {
        super(message);
    }

    public KafkaAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
