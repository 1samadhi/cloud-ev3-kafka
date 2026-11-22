package com.pedidos360.notificaciones.Config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La topología es el contrato con ms-carrito (§9): si un argumento cambia, el
 * segundo servicio en declarar recibe PRECONDITION_FAILED. Estos tests fijan los
 * nombres y argumentos EXACTOS.
 */
class TopologiaRabbitConfigTest {

    private Declarables topologia;

    @BeforeEach
    void crear() {
        topologia = TopologiaRabbitConfig.crearTopologia(new TopologiaRabbitProperties());
    }

    private Queue cola(String nombre) {
        return topologia.getDeclarablesByType(Queue.class).stream()
                .filter(q -> q.getName().equals(nombre)).findFirst()
                .orElseThrow(() -> new AssertionError("No se declaró la cola " + nombre));
    }

    private Exchange exchange(String nombre) {
        return topologia.getDeclarablesByType(Exchange.class).stream()
                .filter(e -> e.getName().equals(nombre)).findFirst()
                .orElseThrow(() -> new AssertionError("No se declaró el exchange " + nombre));
    }

    @Test
    void colaClienteEsQuorumConDlxAtLeastOnceYLimiteDeEntregas() {
        Queue cliente = cola("notificaciones.cliente");

        assertThat(cliente.isDurable()).isTrue();
        assertThat(cliente.isExclusive()).isFalse();
        assertThat(cliente.isAutoDelete()).isFalse();
        // containsExactlyInAnyOrderEntriesOf: ni un argumento de más ni de menos
        // (uno extra también rompería la equivalencia con ms-carrito).
        assertThat(cliente.getArguments()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "x-queue-type", "quorum",
                "x-dead-letter-exchange", "pedidos360.notificaciones.dlx",
                "x-dead-letter-strategy", "at-least-once",
                "x-overflow", "reject-publish",
                "x-delivery-limit", 5));
        // Integer y no Long: RabbitMQ compara también el tipo AMQP del argumento.
        assertThat(cliente.getArguments().get("x-delivery-limit")).isInstanceOf(Integer.class);
    }

    @Test
    void auditoriaYDlqSonQuorumSinDlx() {
        assertThat(cola("notificaciones.auditoria").getArguments())
                .containsExactlyInAnyOrderEntriesOf(Map.of("x-queue-type", "quorum"));
        assertThat(cola("notificaciones.cliente.dlq").getArguments())
                .containsExactlyInAnyOrderEntriesOf(Map.of("x-queue-type", "quorum"));
        assertThat(topologia.getDeclarablesByType(Queue.class)).hasSize(3)
                .allMatch(Queue::isDurable);
    }

    @Test
    void exchangesTopicYFanoutDurables() {
        Exchange principal = exchange("pedidos360.notificaciones");
        Exchange dlx = exchange("pedidos360.notificaciones.dlx");

        assertThat(principal.getType()).isEqualTo(ExchangeTypes.TOPIC);
        assertThat(dlx.getType()).isEqualTo(ExchangeTypes.FANOUT);
        assertThat(principal.isDurable()).isTrue();
        assertThat(dlx.isDurable()).isTrue();
        assertThat(principal.isAutoDelete()).isFalse();
        assertThat(dlx.isAutoDelete()).isFalse();
    }

    @Test
    void bindingsPorPatron() {
        List<String> bindings = topologia.getDeclarablesByType(Binding.class).stream()
                .map(b -> b.getExchange() + " -[" + b.getRoutingKey() + "]-> " + b.getDestination())
                .toList();

        assertThat(bindings).containsExactlyInAnyOrder(
                "pedidos360.notificaciones -[carrito.*]-> notificaciones.cliente",
                "pedidos360.notificaciones -[pedido.*]-> notificaciones.cliente",
                "pedidos360.notificaciones -[envio.*]-> notificaciones.cliente",
                "pedidos360.notificaciones -[#]-> notificaciones.auditoria",
                "pedidos360.notificaciones.dlx -[]-> notificaciones.cliente.dlq");
    }
}
