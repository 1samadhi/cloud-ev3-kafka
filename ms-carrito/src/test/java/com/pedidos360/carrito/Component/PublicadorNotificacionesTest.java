package com.pedidos360.carrito.Component;

import com.pedidos360.carrito.Config.RabbitTopologiaProperties;
import com.pedidos360.carrito.DTO.NotificacionDTO;
import com.pedidos360.carrito.DTO.TipoNotificacion;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Productor RabbitMQ con RabbitTemplate mockeado (no hay broker embebido para RabbitMQ). */
class PublicadorNotificacionesTest {

    final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    final Clock reloj = Clock.fixed(Instant.parse("2026-11-25T15:00:00Z"), ZoneOffset.UTC);
    final PublicadorNotificaciones publicador = new PublicadorNotificaciones(rabbit, new RabbitTopologiaProperties(), reloj);

    @Test
    void publicaEnElExchangeConLaRoutingKeyComoTipoYPropiedadesDeEntrega() {
        publicador.publicar(TipoNotificacion.PEDIDO_PAGADO, "u1", "Pago confirmado", "Recibimos tu pago", "P-7",
                Map.of("total", "34990"));

        ArgumentCaptor<Object> cuerpo = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> procesador = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> correlacion = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit).convertAndSend(eq("pedidos360.notificaciones"), eq("pedido.pagado"), cuerpo.capture(),
                procesador.capture(), correlacion.capture());

        NotificacionDTO n = (NotificacionDTO) cuerpo.getValue();
        assertThat(n.getTipo()).isEqualTo("pedido.pagado");
        assertThat(n.getUsuarioId()).isEqualTo("u1");
        assertThat(n.getIdPedido()).isEqualTo("P-7");
        assertThat(n.getTitulo()).isEqualTo("Pago confirmado");
        assertThat(n.getDatos()).containsEntry("total", "34990");
        assertThat(n.getTimestamp()).isEqualTo(reloj.millis());
        assertThat(n.getIdNotificacion()).hasSize(36); // UUID
        // correlationId = idNotificacion: se sigue el mismo mensaje en el ack del broker y en el consumidor.
        assertThat(correlacion.getValue().getId()).isEqualTo(n.getIdNotificacion());

        Message mensaje = procesador.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        MessageProperties p = mensaje.getMessageProperties();
        assertThat(p.getMessageId()).isEqualTo(n.getIdNotificacion());
        assertThat(p.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(p.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(p.getAppId()).isEqualTo("ms-carrito");
    }

    @Test
    void notificacionDeCarritoSinPedido() {
        publicador.publicar(TipoNotificacion.CARRITO_PRODUCTO_AGREGADO, "u2", "t", "m", null, null);
        ArgumentCaptor<Object> cuerpo = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(eq("pedidos360.notificaciones"), eq("carrito.producto-agregado"), cuerpo.capture(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        NotificacionDTO n = (NotificacionDTO) cuerpo.getValue();
        assertThat(n.getIdPedido()).isNull();
        assertThat(n.getDatos()).isEmpty();
    }

    @Test
    void rabbitCaidoNoRompeLaOperacionDeNegocio() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class),
                        any(CorrelationData.class));
        assertThatCode(() -> publicador.publicar(TipoNotificacion.PEDIDO_CREADO, "u1", "t", "m", "P-1", Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void callbacksDeConfirmacionYDevolucionNoLanzan() {
        CallbacksConfirmacionRabbit callbacks = new CallbacksConfirmacionRabbit();
        assertThatCode(() -> {
            callbacks.confirm(new CorrelationData("id-1"), true, null);
            callbacks.confirm(new CorrelationData("id-2"), false, "reject-publish");
            callbacks.confirm(null, false, "sin correlación");
        }).doesNotThrowAnyException();
    }
}
