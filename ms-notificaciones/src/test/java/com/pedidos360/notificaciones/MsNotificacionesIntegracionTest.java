package com.pedidos360.notificaciones;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.notificaciones.Component.ConsumidorDlq;
import com.pedidos360.notificaciones.DTO.NotificacionDTO;
import com.pedidos360.notificaciones.Repository.MensajeFallidoRepositorio;
import com.pedidos360.notificaciones.Service.ServicioNotificaciones;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contexto completo sin broker: listeners registrados pero detenidos. */
@SpringBootTest
@AutoConfigureMockMvc
class MsNotificacionesIntegracionTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RabbitListenerEndpointRegistry registro;

    @Autowired
    private Declarables topologiaNotificaciones;

    @Autowired
    private Map<String, SimpleRabbitListenerContainerFactory> fabricas;

    @Autowired
    private ServicioNotificaciones servicio;

    @Autowired
    private ConsumidorDlq consumidorDlq;

    @Autowired
    private MensajeFallidoRepositorio fallidos;

    @Test
    void registraLosTresListenersSinArrancarlosYLaTopologia() {
        assertThat(registro.getListenerContainerIds())
                .containsExactlyInAnyOrder("consumidor-notificaciones-cliente", "consumidor-auditoria", "consumidor-dlq");
        assertThat(registro.getListenerContainers()).noneMatch(c -> c.isRunning());
        assertThat(fabricas).containsKeys("rabbitListenerContainerFactory", "fabricaMensajesCrudos", "fabricaDlq");
        assertThat(topologiaNotificaciones.getDeclarables()).hasSize(10);
    }

    @Test
    void elStreamSseRecibeLaNotificacionProcesada() throws Exception {
        MvcResult resultado = mvc.perform(get("/api/notificaciones/stream").param("usuarioId", "u-stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        servicio.procesar(NotificacionDTO.builder()
                .idNotificacion(UUID.randomUUID().toString())
                .usuarioId("u-stream")
                .tipo("envio.estado-actualizado")
                .titulo("Tu pedido va en camino")
                .mensaje("El pedido P-9 salió a reparto")
                .idPedido("P-9")
                .timestamp(System.currentTimeMillis())
                .build());

        String cuerpo = resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(resultado.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(cuerpo).contains("event:conectado")
                .contains("event:notificacion")
                .contains("Tu pedido va en camino");
    }

    @Test
    void elMensajeMuertoQuedaEnMensajeFallidoYSeVeEnElEndpointAdmin() throws Exception {
        MessageProperties props = new MessageProperties();
        props.setMessageId("muerto-1");
        props.setHeader("x-death", List.of(Map.of("queue", "notificaciones.cliente", "reason", "rejected",
                "count", 2L, "exchange", "pedidos360.notificaciones", "routing-keys", List.of("pedido.creado"))));
        consumidorDlq.recibir(new Message("{\"usuarioId\":null}".getBytes(StandardCharsets.UTF_8), props));

        assertThat(fallidos.findAll()).anySatisfy(f -> {
            assertThat(f.getMessageId()).isEqualTo("muerto-1");
            assertThat(f.getMotivo()).isEqualTo("rejected");
            assertThat(f.getConteo()).isEqualTo(2L);
            assertThat(f.getRoutingKeys()).isEqualTo("pedido.creado");
        });

        mvc.perform(get("/api/notificaciones/fallidas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].messageId").value("muerto-1"))
                .andExpect(jsonPath("$[0].colaOrigen").value("notificaciones.cliente"));
    }

    @Test
    void elJsonDelProductorSeConvierteAlDtoLocal() throws Exception {
        // Simula lo que publica ms-carrito: JSON con un campo que este servicio
        // no conoce y el __TypeId__ de la clase del productor.
        var conversor = new org.springframework.amqp.support.converter.Jackson2JsonMessageConverter(new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("__TypeId__", "com.pedidos360.carrito.DTO.NotificacionDTO");
        props.setInferredArgumentType(NotificacionDTO.class);
        String json = "{\"idNotificacion\":\"a\",\"usuarioId\":\"u\",\"tipo\":\"pedido.creado\","
                + "\"titulo\":\"t\",\"mensaje\":\"m\",\"campoNuevo\":1}";

        Object convertido = conversor.fromMessage(new Message(json.getBytes(StandardCharsets.UTF_8), props));

        assertThat(convertido).isInstanceOf(NotificacionDTO.class);
        assertThat(((NotificacionDTO) convertido).getUsuarioId()).isEqualTo("u");
    }
}
