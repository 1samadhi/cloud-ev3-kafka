package com.pedidos360.notificaciones;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ms-notificaciones: el extremo consumidor de RabbitMQ.
 *
 * Por qué RabbitMQ y no Kafka para esto: una notificación es un mensaje punto a
 * punto que tiene que llegar UNA vez a su destinatario, con confirmación por
 * mensaje y un lugar explícito (DLQ) para lo que no se pudo entregar. No
 * interesa reprocesar el historial (eso es lo que Kafka hace bien).
 *
 * EnableScheduling: el heartbeat SSE cada 20 s.
 */
@SpringBootApplication
@EnableScheduling
public class MsNotificacionesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsNotificacionesApplication.class, args);
    }
}
