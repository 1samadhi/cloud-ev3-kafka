package com.pedidos360.notificaciones.Component;

import com.pedidos360.notificaciones.DTO.MensajeMuertoDTO;
import com.pedidos360.notificaciones.Service.ServicioMensajesFallidos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class ConsumidorDlqTest {

    /** Forma real del header x-death que agrega RabbitMQ al dead-letterear. */
    private static Message mensajeMuerto() {
        MessageProperties props = new MessageProperties();
        props.setMessageId("m-42");
        props.setHeader("x-death", List.of(Map.of(
                "queue", "notificaciones.cliente",
                "reason", "rejected",
                "count", 1L,
                "exchange", "pedidos360.notificaciones",
                "routing-keys", List.of("pedido.pagado"))));
        return new Message("{\"tipo\":\"pedido.pagado\"}".getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    void logueaErrorConLosHeadersXDeathYLoRegistra(CapturedOutput salida) {
        ServicioMensajesFallidos servicio = mock(ServicioMensajesFallidos.class);
        new ConsumidorDlq(servicio).recibir(mensajeMuerto());

        assertThat(salida.getOut())
                .contains("ERROR")
                .contains("MENSAJE MUERTO en DLQ")
                .contains("colaOrigen=notificaciones.cliente")
                .contains("motivo=rejected")
                .contains("conteo=1")
                .contains("routingKeys=[pedido.pagado]")
                .contains("messageId=m-42");

        ArgumentCaptor<MensajeMuertoDTO> captor = ArgumentCaptor.forClass(MensajeMuertoDTO.class);
        verify(servicio).registrar(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new MensajeMuertoDTO("m-42", "notificaciones.cliente",
                "rejected", 1L, "pedidos360.notificaciones", List.of("pedido.pagado"),
                "{\"tipo\":\"pedido.pagado\"}"));
    }

    @Test
    void mensajeSinXDeathNoRompeElListener() {
        Message sinHeader = new Message("basura".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        MensajeMuertoDTO muerto = ConsumidorDlq.extraer(sinHeader);

        assertThat(muerto.colaOrigen()).isEqualTo("desconocido");
        assertThat(muerto.motivo()).isEqualTo("sin-x-death");
        assertThat(muerto.cuerpo()).isEqualTo("basura");
    }

    @Test
    void motivoDeliveryLimitDeLaColaQuorum() {
        MessageProperties props = new MessageProperties();
        props.setHeader("x-death", List.of(Map.of("queue", "notificaciones.cliente",
                "reason", "delivery_limit", "count", 1L)));

        MensajeMuertoDTO muerto = ConsumidorDlq.extraer(new Message(new byte[0], props));

        assertThat(muerto.motivo()).isEqualTo("delivery_limit");
        assertThat(muerto.routingKeys()).isEmpty();
    }
}
