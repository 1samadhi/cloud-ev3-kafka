package com.pedidos360.rabbitadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ms-rabbit-admin: administración del clúster RabbitMQ de Pedidos360.
 *
 * Usa DOS canales hacia el broker, cada uno para lo que sabe hacer:
 *  - AMQP (AmqpAdmin/RabbitAdmin) para declarar y borrar: es el mismo protocolo
 *    con el que los microservicios declaran su topología, así que lo que se
 *    crea acá queda exactamente igual que si lo declarara un servicio.
 *  - La API HTTP de management para LISTAR: AMQP no tiene operación para
 *    enumerar colas/exchanges/bindings ni para ver el estado de los nodos.
 */
@SpringBootApplication
public class MsRabbitAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsRabbitAdminApplication.class, args);
    }
}
