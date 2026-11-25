package com.pedidos360.rabbitadmin.Controller;

import com.pedidos360.rabbitadmin.DTO.ColaDTO;
import com.pedidos360.rabbitadmin.DTO.CrearColaRequestDTO;
import com.pedidos360.rabbitadmin.DTO.CrearExchangeRequestDTO;
import com.pedidos360.rabbitadmin.DTO.BindingRequestDTO;
import com.pedidos360.rabbitadmin.DTO.EstadoClusterDTO;
import com.pedidos360.rabbitadmin.DTO.RespuestaOperacionDTO;
import com.pedidos360.rabbitadmin.Exception.ConflictoException;
import com.pedidos360.rabbitadmin.Exception.ManagementNoDisponibleException;
import com.pedidos360.rabbitadmin.Exception.RecursoNoEncontradoException;
import com.pedidos360.rabbitadmin.Exception.RecursoProtegidoException;
import com.pedidos360.rabbitadmin.Exception.RecursoYaExisteException;
import com.pedidos360.rabbitadmin.Service.ServicioRabbitAdmin;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.impl.AMQImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Validaciones de entrada y códigos HTTP, con el servicio mockeado. */
@WebMvcTest(RabbitAdminController.class)
class RabbitAdminControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServicioRabbitAdmin servicio;

    private static final RespuestaOperacionDTO OK = RespuestaOperacionDTO.de("ok", "cola", "x", Map.of());

    /** Lo que llega cuando el broker cierra el CANAL con un código (no la conexión). */
    private static AmqpIOException errorDeCanal(int codigo, String texto) {
        ShutdownSignalException cierre = new ShutdownSignalException(false, false,
                new AMQImpl.Channel.Close(codigo, texto, 50, 10), null);
        return new AmqpIOException(new IOException(cierre));
    }

    // ------------------------------------------------------------- listados

    @Test
    void listarColasDevuelve200ConLosCampos() throws Exception {
        when(servicio.listarColas(false)).thenReturn(List.of(new ColaDTO("notificaciones.cliente", "/", "quorum",
                true, false, false, 3L, 2L, 1L, 1, "running", "rabbit@rabbitmq2",
                List.of("rabbit@rabbitmq1", "rabbit@rabbitmq2", "rabbit@rabbitmq3"), List.of("rabbit@rabbitmq2"),
                Map.of("x-queue-type", "quorum"), true)));

        mvc.perform(get("/api/rabbit/colas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("notificaciones.cliente"))
                .andExpect(jsonPath("$[0].tipo").value("quorum"))
                .andExpect(jsonPath("$[0].mensajes").value(3))
                .andExpect(jsonPath("$[0].mensajesSinConfirmar").value(1))
                .andExpect(jsonPath("$[0].miembros.length()").value(3))
                .andExpect(jsonPath("$[0].protegida").value(true));
    }

    @Test
    void listarColasSoloConMensajes() throws Exception {
        when(servicio.listarColas(true)).thenReturn(List.of());
        mvc.perform(get("/api/rabbit/colas").param("soloConMensajes", "true")).andExpect(status().isOk());
        verify(servicio).listarColas(true);
    }

    @Test
    void cluster200() throws Exception {
        when(servicio.estadoCluster()).thenReturn(new EstadoClusterDTO("pedidos360", 3, 2, true, false, false,
                List.of(), "http://rabbitmq2:15672", 1L));
        mvc.perform(get("/api/rabbit/cluster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreCluster").value("pedidos360"))
                .andExpect(jsonPath("$.nodosCorriendo").value(2))
                .andExpect(jsonPath("$.hayQuorum").value(true));
    }

    @Test
    void bindingsPorDefectoOcultaImplicitos() throws Exception {
        when(servicio.listarBindings(false)).thenReturn(List.of());
        mvc.perform(get("/api/rabbit/bindings")).andExpect(status().isOk());
        verify(servicio).listarBindings(false);
    }

    // ------------------------------------------------------- crear: validación

    private org.springframework.test.web.servlet.ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void crearColaSinNombreEs400ConFormatoDeError() throws Exception {
        postJson("/api/rabbit/colas", "{\"tipo\":\"quorum\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Petición inválida"))
                .andExpect(jsonPath("$.detalle").value(containsString("nombre")))
                .andExpect(jsonPath("$.timestamp").isNumber());
        verifyNoInteractions(servicio);
    }

    @Test
    void crearColaConNombreEnBlancoOCaracteresInvalidosEs400() throws Exception {
        postJson("/api/rabbit/colas", "{\"nombre\":\"   \"}").andExpect(status().isBadRequest());
        postJson("/api/rabbit/colas", "{\"nombre\":\"cola con espacios\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("sólo admite")));
        postJson("/api/rabbit/colas", "{\"nombre\":\"a/b\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void crearColaConPrefijoAmqEs400() throws Exception {
        postJson("/api/rabbit/colas", "{\"nombre\":\"amq.mia\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("reservado")));
        verifyNoInteractions(servicio);
    }

    @Test
    void crearColaConTipoInvalidoEs400() throws Exception {
        postJson("/api/rabbit/colas", "{\"nombre\":\"q1\",\"tipo\":\"lazy\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("'quorum' o 'classic'")));
        postJson("/api/rabbit/colas", "{\"nombre\":\"q1\",\"tipo\":\"stream\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void crearColaQuorumNoDurableEs400() throws Exception {
        postJson("/api/rabbit/colas", "{\"nombre\":\"q1\",\"tipo\":\"quorum\",\"durable\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("siempre es durable")));
        verifyNoInteractions(servicio);
    }

    @Test
    void limiteEntregasEnClassicEs400YCeroTambien() throws Exception {
        postJson("/api/rabbit/colas", "{\"nombre\":\"q1\",\"tipo\":\"classic\",\"limiteEntregas\":5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("sólo aplica a colas quorum")));
        postJson("/api/rabbit/colas", "{\"nombre\":\"q1\",\"limiteEntregas\":0}").andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void crearColaValidaEs201YAplicaDefaults() throws Exception {
        when(servicio.crearCola(any())).thenReturn(OK);

        postJson("/api/rabbit/colas", "{\"nombre\":\"pedidos.nuevos\",\"tipo\":\"QUORUM\"}")
                .andExpect(status().isCreated());

        ArgumentCaptor<CrearColaRequestDTO> req = ArgumentCaptor.forClass(CrearColaRequestDTO.class);
        verify(servicio).crearCola(req.capture());
        assertThat(req.getValue().tipo()).isEqualTo("quorum");
        assertThat(req.getValue().durable()).isTrue();
    }

    @Test
    void crearColaSinTipoEsQuorum() throws Exception {
        when(servicio.crearCola(any())).thenReturn(OK);
        postJson("/api/rabbit/colas", "{\"nombre\":\"q1\"}").andExpect(status().isCreated());
        ArgumentCaptor<CrearColaRequestDTO> req = ArgumentCaptor.forClass(CrearColaRequestDTO.class);
        verify(servicio).crearCola(req.capture());
        assertThat(req.getValue().esQuorum()).isTrue();
    }

    @Test
    void jsonMalFormadoEs400() throws Exception {
        postJson("/api/rabbit/colas", "{\"nombre\":")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("JSON")));
    }

    @Test
    void sinContentTypeJsonEs415() throws Exception {
        mvc.perform(post("/api/rabbit/colas").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void crearExchangeConTipoInvalidoOSinTipoEs400() throws Exception {
        postJson("/api/rabbit/exchanges", "{\"nombre\":\"e1\",\"tipo\":\"x-delayed-message\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("direct, topic, fanout o headers")));
        postJson("/api/rabbit/exchanges", "{\"nombre\":\"e1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("obligatorio")));
        postJson("/api/rabbit/exchanges", "{\"nombre\":\"amq.nuevo\",\"tipo\":\"topic\"}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void crearExchangeValidoEs201() throws Exception {
        when(servicio.crearExchange(any())).thenReturn(OK);
        for (String tipo : List.of("direct", "topic", "fanout", "headers")) {
            postJson("/api/rabbit/exchanges", "{\"nombre\":\"e." + tipo + "\",\"tipo\":\"" + tipo + "\"}")
                    .andExpect(status().isCreated());
        }
        ArgumentCaptor<CrearExchangeRequestDTO> req = ArgumentCaptor.forClass(CrearExchangeRequestDTO.class);
        verify(servicio, org.mockito.Mockito.times(4)).crearExchange(req.capture());
        assertThat(req.getAllValues().get(0).durable()).isTrue();
        assertThat(req.getAllValues().get(0).autoEliminar()).isFalse();
    }

    @Test
    void crearBindingSinRoutingKeyEs400PeroVaciaEsValida() throws Exception {
        postJson("/api/rabbit/bindings", "{\"origen\":\"amq.fanout\",\"destino\":\"q1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("routingKey")));
        postJson("/api/rabbit/bindings", "{\"origen\":\"\",\"destino\":\"q1\",\"routingKey\":\"q1\"}")
                .andExpect(status().isBadRequest());
        postJson("/api/rabbit/bindings", "{\"origen\":\"amq.fanout\",\"destino\":\"q1\",\"routingKey\":\"\",\"tipoDestino\":\"topic\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("tipoDestino")));
        verifyNoInteractions(servicio);

        when(servicio.crearBinding(any())).thenReturn(OK);
        postJson("/api/rabbit/bindings", "{\"origen\":\"amq.fanout\",\"destino\":\"q1\",\"routingKey\":\"\"}")
                .andExpect(status().isCreated());
        ArgumentCaptor<BindingRequestDTO> req = ArgumentCaptor.forClass(BindingRequestDTO.class);
        verify(servicio).crearBinding(req.capture());
        assertThat(req.getValue().tipoDestino()).isEqualTo("queue");
        assertThat(req.getValue().routingKey()).isEmpty();
    }

    // --------------------------------------------------------------- borrar

    @Test
    void borrarColaPasaElNombreConPuntosCompletoYLosFlags() throws Exception {
        when(servicio.borrarCola(anyString(), anyBoolean(), anyBoolean())).thenReturn(OK);

        mvc.perform(delete("/api/rabbit/colas/notificaciones.cliente.dlq")).andExpect(status().isOk());
        verify(servicio).borrarCola("notificaciones.cliente.dlq", false, false);

        mvc.perform(delete("/api/rabbit/colas/prueba.classic").param("siVacia", "true").param("forzar", "true"))
                .andExpect(status().isOk());
        verify(servicio).borrarCola("prueba.classic", true, true);
    }

    @Test
    void borrarConFlagNoBooleanoEs400() throws Exception {
        mvc.perform(delete("/api/rabbit/colas/q1").param("siVacia", "quizas")).andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void borrarColaConNombreEnBlancoEs400() throws Exception {
        mvc.perform(delete("/api/rabbit/colas/{nombre}", " ")).andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void borrarProtegidoEs403() throws Exception {
        when(servicio.borrarExchange("amq.topic", false))
                .thenThrow(new RecursoProtegidoException("No se borra el exchange 'amq.topic'"));
        mvc.perform(delete("/api/rabbit/exchanges/amq.topic"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Recurso protegido"));
    }

    @Test
    void borrarInexistenteEs404() throws Exception {
        when(servicio.borrarCola("no.existe", false, false)).thenThrow(new RecursoNoEncontradoException("no existe"));
        mvc.perform(delete("/api/rabbit/colas/no.existe")).andExpect(status().isNotFound());
    }

    @Test
    void crearExistenteYColaConMensajesSon409() throws Exception {
        when(servicio.crearCola(any())).thenThrow(new RecursoYaExisteException("ya existe"));
        postJson("/api/rabbit/colas", "{\"nombre\":\"q1\"}").andExpect(status().isConflict());

        when(servicio.borrarCola("q1", true, false)).thenThrow(new ConflictoException("tiene 7 mensajes"));
        mvc.perform(delete("/api/rabbit/colas/q1").param("siVacia", "true")).andExpect(status().isConflict());
    }

    @Test
    void borrarBindingConCuerpoYForzar() throws Exception {
        when(servicio.borrarBinding(any(), eq(true))).thenReturn(OK);
        mvc.perform(delete("/api/rabbit/bindings").param("forzar", "true").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origen\":\"pedidos360.notificaciones\",\"destino\":\"notificaciones.cliente\",\"routingKey\":\"pedido.*\"}"))
                .andExpect(status().isOk());
        verify(servicio).borrarBinding(any(), eq(true));
    }

    @Test
    void purgar200() throws Exception {
        when(servicio.purgarCola("notificaciones.cliente.dlq")).thenReturn(OK);
        mvc.perform(post("/api/rabbit/colas/notificaciones.cliente.dlq/purgar")).andExpect(status().isOk());
    }

    // ------------------------------------------------ errores del broker

    @Test
    void preconditionFailedDelBrokerEs409ConElTextoDeRabbit() throws Exception {
        when(servicio.crearCola(any())).thenThrow(errorDeCanal(406,
                "PRECONDITION_FAILED - inequivalent arg 'x-queue-type' for queue 'q1' in vhost '/': received 'classic' but current is 'quorum'"));
        postJson("/api/rabbit/colas", "{\"nombre\":\"q1\",\"tipo\":\"classic\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detalle").value(containsString("inequivalent arg")));
    }

    @Test
    void notFoundYAccessRefusedDelBrokerSon404Y403() throws Exception {
        when(servicio.crearBinding(any())).thenThrow(errorDeCanal(404, "NOT_FOUND - no exchange 'x' in vhost '/'"));
        postJson("/api/rabbit/bindings", "{\"origen\":\"x\",\"destino\":\"q1\",\"routingKey\":\"\"}")
                .andExpect(status().isNotFound());

        when(servicio.borrarExchange("amq.direct", true))
                .thenThrow(errorDeCanal(403, "ACCESS_REFUSED - operation not permitted on exchange 'amq.direct'"));
        mvc.perform(delete("/api/rabbit/exchanges/amq.direct").param("forzar", "true")).andExpect(status().isForbidden());
    }

    @Test
    void brokerCaidoEs503() throws Exception {
        when(servicio.purgarCola("q1")).thenThrow(new AmqpConnectException(new ConnectException("Connection refused")));
        mvc.perform(post("/api/rabbit/colas/q1/purgar"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("RabbitMQ no disponible"))
                .andExpect(jsonPath("$.detalle").value(containsString("Connection refused")));

        // Cierre de CONEXIÓN (hard error, p. ej. nodo apagándose): también 503.
        ShutdownSignalException conexion = new ShutdownSignalException(true, false,
                new AMQImpl.Connection.Close(320, "CONNECTION_FORCED - broker forced connection closure with reason 'shutdown'", 0, 0), null);
        when(servicio.borrarCola("q2", false, false)).thenThrow(new AmqpIOException(new IOException(conexion)));
        mvc.perform(delete("/api/rabbit/colas/q2")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void managementCaidaEs503YCredencialesRechazadasEs502() throws Exception {
        when(servicio.estadoCluster()).thenThrow(new ManagementNoDisponibleException("Ningún nodo respondió"));
        mvc.perform(get("/api/rabbit/cluster"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("API de management no disponible"));

        when(servicio.listarExchanges()).thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED,
                "Unauthorized", null, null, null));
        mvc.perform(get("/api/rabbit/exchanges"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detalle").value(containsString("Credenciales")));
    }

    // ------------------------------------------------------------- varios

    @Test
    void metodoNoSoportadoEs405() throws Exception {
        mvc.perform(put("/api/rabbit/colas")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void preflightCorsDesdeLaConsolaPermiteDelete() throws Exception {
        mvc.perform(options("/api/rabbit/colas/q1")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")));
    }
}
