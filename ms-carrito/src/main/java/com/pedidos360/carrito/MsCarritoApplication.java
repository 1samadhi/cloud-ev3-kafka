package com.pedidos360.carrito;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ms-carrito (8087): carrito, cupones, envío/retiro y pedidos.
 *
 * Regla de oro (§1): REST para lo transaccional (reservar/liberar stock en ms-productos),
 * RabbitMQ para notificaciones con entrega garantizada, Kafka para eventos analíticos.
 * Un fallo de cualquiera de los dos brokers nunca rompe la operación de negocio.
 */
@SpringBootApplication
@EnableScheduling
public class MsCarritoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsCarritoApplication.class, args);
    }
}
