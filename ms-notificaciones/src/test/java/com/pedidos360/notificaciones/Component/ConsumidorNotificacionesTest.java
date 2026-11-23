package com.pedidos360.notificaciones.Component;

import com.pedidos360.notificaciones.DTO.NotificacionDTO;
import com.pedidos360.notificaciones.Service.ServicioNotificaciones;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** El listener sólo valida y delega: lo inválido sale con rechazo sin reencolar. */
class ConsumidorNotificacionesTest {

    private static final jakarta.validation.ValidatorFactory FABRICA = Validation.buildDefaultValidatorFactory();

    private ServicioNotificaciones servicio;
    private ConsumidorNotificaciones consumidor;

    @BeforeEach
    void preparar() {
        Validator validator = FABRICA.getValidator();
        servicio = mock(ServicioNotificaciones.class);
        consumidor = new ConsumidorNotificaciones(validator, servicio);
    }

    @AfterAll
    static void cerrar() {
        FABRICA.close();
    }

    private static NotificacionDTO valida() {
        return NotificacionDTO.builder()
                .idNotificacion(UUID.randomUUID().toString())
                .usuarioId("u-1")
                .tipo("pedido.pagado")
                .titulo("Pago confirmado")
                .mensaje("Tu pedido P-1 fue pagado")
                .idPedido("P-1")
                .datos(Map.of("total", 19990))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private static Message mensajeAmqp(String routingKey) {
        MessageProperties props = new MessageProperties();
        props.setConsumerQueue("notificaciones.cliente");
        props.setReceivedRoutingKey(routingKey);
        props.setMessageId("m-1");
        return new Message(new byte[0], props);
    }

    @Test
    void mensajeValidoSeDelegaAlServicio() {
        NotificacionDTO dto = valida();
        consumidor.recibir(dto, mensajeAmqp("pedido.pagado"));
        verify(servicio).procesar(dto);
    }

    @Test
    void sinUsuarioIdSeRechazaSinReencolar() {
        NotificacionDTO dto = valida();
        dto.setUsuarioId(null);

        assertThatThrownBy(() -> consumidor.recibir(dto, mensajeAmqp("pedido.pagado")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("usuarioId es obligatorio");
        verify(servicio, never()).procesar(any());
    }

    @Test
    void tipoDesconocidoSeRechazaSinReencolar() {
        NotificacionDTO dto = valida();
        dto.setTipo("pedido.inventado");

        assertThatThrownBy(() -> consumidor.recibir(dto, mensajeAmqp("pedido.inventado")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("pedido.inventado");
        verify(servicio, never()).procesar(any());
    }

    @Test
    void cuerpoNuloSeRechazaSinReencolar() {
        assertThatThrownBy(() -> consumidor.recibir(null, mensajeAmqp("pedido.pagado")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(servicio, never()).procesar(any());
    }

    @Test
    void errorTransitorioDelServicioSePropagaParaReintentar() {
        NotificacionDTO dto = valida();
        org.mockito.Mockito.doThrow(new org.springframework.dao.QueryTimeoutException("timeout"))
                .when(servicio).procesar(dto);

        // NO debe convertirse en rechazo: la política de reintentos tiene que verla.
        assertThatThrownBy(() -> consumidor.recibir(dto, mensajeAmqp("pedido.pagado")))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class)
                .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);
    }
}
