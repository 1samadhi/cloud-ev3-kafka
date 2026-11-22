package com.pedidos360.notificaciones.Config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología de notificaciones declarada como {@link Declarables}.
 *
 * Se declara en AMBOS lados (ms-carrito produce, este servicio consume): el que
 * arranque primero la crea y redeclarar con los mismos argumentos es inocuo. Así
 * ningún servicio depende del orden de arranque ni de un script externo. El
 * RabbitAdmin de Spring la declara recién cuando se abre la primera conexión, y
 * la vuelve a declarar en cada reconexión (si se borró una cola a mano, vuelve).
 *
 * CUIDADO: los argumentos deben ser idénticos en los dos servicios. Si difieren,
 * RabbitMQ responde PRECONDITION_FAILED ("inequivalent arg") y el que llegó
 * segundo no puede consumir/publicar.
 */
@Configuration
public class TopologiaRabbitConfig {

    /** Routing keys que la cola cliente recibe (todas las de §9). */
    public static final String[] PATRONES_COLA_CLIENTE = {"carrito.*", "pedido.*", "envio.*"};

    /** La auditoría lo recibe todo: demuestra el ruteo por patrón del exchange topic. */
    public static final String PATRON_AUDITORIA = "#";

    @Bean
    public Declarables topologiaNotificaciones(TopologiaRabbitProperties props) {
        return crearTopologia(props);
    }

    /**
     * Estático y sin Spring para poder probar los argumentos exactos en un test
     * unitario (son el contrato con ms-carrito).
     */
    public static Declarables crearTopologia(TopologiaRabbitProperties props) {
        // topic: permite que cada cola elija qué recibe por patrón (carrito.*,
        // pedido.*, #) sin que el productor sepa cuántas colas hay.
        TopicExchange exchange = ExchangeBuilder.topicExchange(props.getExchange())
                .durable(true)
                .build();

        // fanout: a la DLQ va TODO lo que muere en la cola cliente, sin importar
        // su routing key original (que de todos modos queda en x-death).
        FanoutExchange dlx = ExchangeBuilder.fanoutExchange(props.getExchangeDlx())
                .durable(true)
                .build();

        Queue colaCliente = QueueBuilder.durable(props.getColaCliente())
                // quorum: réplica Raft en los 3 nodos; un mensaje confirmado al
                // productor ya está en la mayoría y sobrevive la caída de 1 nodo.
                .quorum()
                // Lo rechazado sin reencolar (payload inválido o reintentos
                // agotados) y lo que supera x-delivery-limit va al DLX -> DLQ.
                .deadLetterExchange(props.getExchangeDlx())
                // at-least-once: el mensaje sólo se borra de esta cola cuando la
                // DLQ confirmó que lo tiene. Con el default (at-most-once) un
                // mensaje muerto se podría perder en el camino a la DLQ.
                .withArgument("x-dead-letter-strategy", "at-least-once")
                // reject-publish: at-least-once EXIGE este overflow (con
                // drop-head RabbitMQ vuelve en silencio a at-most-once). Además,
                // si la cola se llenara, el productor recibe nack y lo loguea en
                // vez de que se descarten mensajes viejos sin aviso.
                .overflow(QueueBuilder.Overflow.rejectPublish)
                // Red de seguridad del broker: si un mensaje "veneno" hace caer
                // al consumidor antes de rechazarlo, se reentregaría para
                // siempre. Tras N entregas el broker lo manda solo al DLX.
                .deliveryLimit(props.getLimiteEntregas())
                .build();

        // Auditoría y DLQ también son quorum (§9: todas replicadas). No llevan
        // DLX: la auditoría sólo loguea y la DLQ es el final del camino.
        Queue colaAuditoria = QueueBuilder.durable(props.getColaAuditoria())
                .quorum()
                .build();

        Queue colaDlq = QueueBuilder.durable(props.getColaDlq())
                .quorum()
                .build();

        Binding carrito = BindingBuilder.bind(colaCliente).to(exchange).with(PATRONES_COLA_CLIENTE[0]);
        Binding pedido = BindingBuilder.bind(colaCliente).to(exchange).with(PATRONES_COLA_CLIENTE[1]);
        Binding envio = BindingBuilder.bind(colaCliente).to(exchange).with(PATRONES_COLA_CLIENTE[2]);
        Binding auditoria = BindingBuilder.bind(colaAuditoria).to(exchange).with(PATRON_AUDITORIA);
        Binding dlq = BindingBuilder.bind(colaDlq).to(dlx);

        return new Declarables(exchange, dlx, colaCliente, colaAuditoria, colaDlq,
                carrito, pedido, envio, auditoria, dlq);
    }
}
