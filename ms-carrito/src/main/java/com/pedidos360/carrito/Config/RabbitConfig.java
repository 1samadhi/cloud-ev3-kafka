package com.pedidos360.carrito.Config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.carrito.Component.CallbacksConfirmacionRabbit;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología de §9. Se declara aquí Y en ms-notificaciones: el que arranque primero la crea y
 * redeclarar con los mismos argumentos es inocuo. Si los argumentos difirieran, RabbitMQ
 * respondería PRECONDITION_FAILED: por eso se copian EXACTAMENTE los del contrato.
 */
@Configuration
public class RabbitConfig {

    /** Patrones de binding de la cola del cliente: todo lo que el usuario debe ver. */
    static final String[] PATRONES_CLIENTE = {"carrito.*", "pedido.*", "envio.*"};
    /** "#" = cualquier routing key: la auditoría demuestra el ruteo por patrón del topic exchange. */
    static final String PATRON_AUDITORIA = "#";

    @Bean
    public Declarables topologiaNotificaciones(RabbitTopologiaProperties p) {
        TopicExchange exchange = ExchangeBuilder.topicExchange(p.getExchange()).durable(true).build();
        // fanout: a la DLQ va todo lo muerto sin importar su routing key original.
        FanoutExchange dlx = ExchangeBuilder.fanoutExchange(p.getExchangeDlx()).durable(true).build();

        Queue cliente = QueueBuilder.durable(p.getColaCliente())
                // quorum: réplica Raft en los 3 nodos; sobrevive a la caída de uno sin perder mensajes.
                .quorum()
                .deadLetterExchange(p.getExchangeDlx())
                // at-least-once: el dead-lettering también se confirma (no se pierde si la DLQ
                // está momentáneamente no disponible). RabbitMQ lo exige junto con reject-publish.
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .overflow(QueueBuilder.Overflow.rejectPublish)
                // Un mensaje que hace fallar al consumidor 5 veces se deja de reentregar (va a la DLQ).
                .deliveryLimit(5)
                .build();
        Queue auditoria = QueueBuilder.durable(p.getColaAuditoria()).quorum().build();
        Queue dlq = QueueBuilder.durable(p.getColaDlq()).quorum().build();

        Binding b1 = BindingBuilder.bind(cliente).to(exchange).with(PATRONES_CLIENTE[0]);
        Binding b2 = BindingBuilder.bind(cliente).to(exchange).with(PATRONES_CLIENTE[1]);
        Binding b3 = BindingBuilder.bind(cliente).to(exchange).with(PATRONES_CLIENTE[2]);
        Binding b4 = BindingBuilder.bind(auditoria).to(exchange).with(PATRON_AUDITORIA);
        Binding b5 = BindingBuilder.bind(dlq).to(dlx);

        return new Declarables(exchange, dlx, cliente, auditoria, dlq, b1, b2, b3, b4, b5);
    }

    /** JSON con el ObjectMapper de Spring (fechas ISO, módulos registrados). Boot lo aplica al RabbitTemplate. */
    @Bean
    public Jackson2JsonMessageConverter conversorJsonRabbit(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /** Engancha los callbacks de confirmación (ack/nack) y de devolución (sin ruta) al template de Boot. */
    @Bean
    public RabbitTemplateCustomizer callbacksRabbitTemplate(CallbacksConfirmacionRabbit callbacks) {
        return template -> {
            template.setConfirmCallback(callbacks);
            template.setReturnsCallback(callbacks);
        };
    }
}
