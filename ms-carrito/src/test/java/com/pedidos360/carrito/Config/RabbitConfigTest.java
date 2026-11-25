package com.pedidos360.carrito.Config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La topología es un contrato con ms-notificaciones: si un argumento difiere, el segundo servicio en
 * arrancar recibe PRECONDITION_FAILED. Estos valores son copia literal de su TopologiaRabbitConfig.
 */
class RabbitConfigTest {

    final Declarables topologia = new RabbitConfig().topologiaNotificaciones(new RabbitTopologiaProperties());

    @Test
    void exchangesTopicYFanoutDurables() {
        Map<String, Exchange> ex = topologia.getDeclarablesByType(Exchange.class).stream()
                .collect(Collectors.toMap(Exchange::getName, e -> e));
        assertThat(ex).containsOnlyKeys("pedidos360.notificaciones", "pedidos360.notificaciones.dlx");
        assertThat(ex.get("pedidos360.notificaciones").getType()).isEqualTo(ExchangeTypes.TOPIC);
        assertThat(ex.get("pedidos360.notificaciones.dlx").getType()).isEqualTo(ExchangeTypes.FANOUT);
        assertThat(ex.values()).allMatch(Exchange::isDurable).noneMatch(Exchange::isAutoDelete);
    }

    @Test
    void colasQuorumConLosArgumentosExactosDeMsNotificaciones() {
        Map<String, Queue> colas = topologia.getDeclarablesByType(Queue.class).stream()
                .collect(Collectors.toMap(Queue::getName, q -> q));
        assertThat(colas).containsOnlyKeys("notificaciones.cliente", "notificaciones.auditoria", "notificaciones.cliente.dlq");
        assertThat(colas.get("notificaciones.cliente").getArguments()).isEqualTo(Map.of(
                "x-queue-type", "quorum",
                "x-dead-letter-exchange", "pedidos360.notificaciones.dlx",
                "x-dead-letter-strategy", "at-least-once",
                "x-overflow", "reject-publish",
                "x-delivery-limit", 5));
        assertThat(colas.get("notificaciones.auditoria").getArguments()).isEqualTo(Map.of("x-queue-type", "quorum"));
        assertThat(colas.get("notificaciones.cliente.dlq").getArguments()).isEqualTo(Map.of("x-queue-type", "quorum"));
        assertThat(colas.values()).allMatch(Queue::isDurable).noneMatch(Queue::isExclusive).noneMatch(Queue::isAutoDelete);
    }

    @Test
    void bindingsPorPatron() {
        var bindings = topologia.getDeclarablesByType(Binding.class).stream()
                .map(b -> b.getDestination() + "<-" + b.getExchange() + ":" + b.getRoutingKey())
                .toList();
        assertThat(bindings).containsExactlyInAnyOrder(
                "notificaciones.cliente<-pedidos360.notificaciones:carrito.*",
                "notificaciones.cliente<-pedidos360.notificaciones:pedido.*",
                "notificaciones.cliente<-pedidos360.notificaciones:envio.*",
                "notificaciones.auditoria<-pedidos360.notificaciones:#",
                "notificaciones.cliente.dlq<-pedidos360.notificaciones.dlx:");
    }
}
