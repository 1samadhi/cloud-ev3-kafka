package com.kafka.admin.Controller;

import com.kafka.admin.DTO.*;
import com.kafka.admin.Exception.ConflictoException;
import com.kafka.admin.Exception.KafkaNoDisponibleException;
import com.kafka.admin.Exception.RecursoNoEncontradoException;
import com.kafka.admin.Exception.SolicitudInvalidaException;
import com.kafka.admin.Service.KafkaAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Códigos HTTP y forma del error de los endpoints, con el servicio mockeado.
 * Verifica también que una validación de cuerpo (400) corta ANTES de llegar
 * al servicio, es decir, antes de tocar el clúster.
 */
@WebMvcTest(KafkaAdminController.class)
class KafkaAdminControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private KafkaAdminService servicio;

    // ------------------------------------------------- configuración tópicos

    @Test
    void getConfiguracionDeTopicoInexistenteDevuelve404ConElCuerpoDeErrorCompleto() throws Exception {
        when(servicio.obtenerConfiguracionTopico("fantasma"))
                .thenThrow(new RecursoNoEncontradoException("El tópico 'fantasma' no existe"));

        mvc.perform(get("/api/topicos/fantasma/configuracion"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No encontrado"))
                .andExpect(jsonPath("$.detalle").value("El tópico 'fantasma' no existe"))
                // "mensaje" y "codigo" los lee la consola React (api.ts): no se pueden perder.
                .andExpect(jsonPath("$.mensaje").value("El tópico 'fantasma' no existe"))
                .andExpect(jsonPath("$.codigo").value(404))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void getConfiguracionDevuelveEntradasYSobrescritas() throws Exception {
        when(servicio.obtenerConfiguracionTopico("catalogo-productos")).thenReturn(new ConfiguracionRecursoDTO(
                "catalogo-productos", "TOPIC",
                List.of(new EntradaConfiguracionDTO("cleanup.policy", "compact", false, "DYNAMIC_TOPIC_CONFIG", false, false, true)),
                Map.of("cleanup.policy", "compact")));

        mvc.perform(get("/api/topicos/catalogo-productos/configuracion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("TOPIC"))
                .andExpect(jsonPath("$.entradas[0].porDefecto").value(false))
                .andExpect(jsonPath("$.entradas[0].editable").value(true))
                .andExpect(jsonPath("$.sobrescritas['cleanup.policy']").value("compact"));
    }

    @Test
    void putConfiguracionFueraDeListaBlancaDevuelve400() throws Exception {
        when(servicio.modificarConfiguracionTopico(eq("pagos-resultado"), any()))
                .thenThrow(new SolicitudInvalidaException("Configuración inválida: 'foo' no está en la lista blanca"));

        mvc.perform(put("/api/topicos/pagos-resultado/configuracion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"establecer\":{\"foo\":\"1\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Solicitud inválida"))
                .andExpect(jsonPath("$.detalle", containsString("lista blanca")));
    }

    @Test
    void putConfiguracionConJsonRotoDevuelve400SinLlamarAlServicio() throws Exception {
        mvc.perform(put("/api/topicos/t/configuracion").contentType(MediaType.APPLICATION_JSON).content("{establecer:"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void putConfiguracionSobreTopicoInexistenteDevuelve404() throws Exception {
        when(servicio.modificarConfiguracionTopico(eq("x"), any()))
                .thenThrow(new RecursoNoEncontradoException("El tópico 'x' no existe"));
        mvc.perform(put("/api/topicos/x/configuracion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"establecer\":{\"retention.ms\":\"1000\"}}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ particiones

    @Test
    void particionesSinCantidadOFueraDeRangoDevuelve400SinLlamarAlServicio() throws Exception {
        mvc.perform(post("/api/topicos/t/particiones").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value("Debe indicar la cantidad total de particiones"));
        mvc.perform(post("/api/topicos/t/particiones").contentType(MediaType.APPLICATION_JSON).content("{\"particiones\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/topicos/t/particiones").contentType(MediaType.APPLICATION_JSON).content("{\"particiones\":5000}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void reducirParticionesDevuelve400() throws Exception {
        when(servicio.aumentarParticiones("payment-attempts", 2)).thenThrow(new SolicitudInvalidaException(
                "El tópico 'payment-attempts' ya tiene 3 particiones: la nueva cantidad debe ser mayor (Kafka no permite reducir particiones)"));

        mvc.perform(post("/api/topicos/payment-attempts/particiones")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"particiones\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle", containsString("no permite reducir")));
    }

    @Test
    void aumentarParticionesDevuelve200ConElResultado() throws Exception {
        when(servicio.aumentarParticiones("interacciones-user", 6)).thenReturn(
                new ResultadoParticionesDTO("interacciones-user", 3, 6, List.of("aviso")));

        mvc.perform(post("/api/topicos/interacciones-user/particiones")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"particiones\":6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.particionesAnteriores").value(3))
                .andExpect(jsonPath("$.particionesActuales").value(6));
    }

    // ------------------------------------------------------- consumer groups

    @Test
    void detalleDeGrupoInexistenteDevuelve404YExistenteDevuelveMiembros() throws Exception {
        when(servicio.obtenerConsumerGroupDetalle("nada"))
                .thenThrow(new RecursoNoEncontradoException("El consumer group 'nada' no existe"));
        mvc.perform(get("/api/consumer-groups/nada")).andExpect(status().isNotFound());

        when(servicio.obtenerConsumerGroupDetalle("grupo-recomendaciones")).thenReturn(new ConsumerGroupDetalleDTO(
                "grupo-recomendaciones", "Stable", new BrokerDTO(2, "kafka2"), "range", false, 1,
                List.of(new MiembroConsumerGroupDTO("m-1", "rec-0", "/10.0.0.5", null,
                        List.of(new AsignacionParticionDTO("interacciones-user", 0))))));
        mvc.perform(get("/api/consumer-groups/grupo-recomendaciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coordinador.id").value(2))
                .andExpect(jsonPath("$.miembros[0].asignaciones[0].topico").value("interacciones-user"));
    }

    @Test
    void resetSinTopicoOSinValorParaTimestampDevuelve400SinLlamarAlServicio() throws Exception {
        mvc.perform(post("/api/consumer-groups/g/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estrategia\":\"EARLIEST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle", containsString("tópico")));
        mvc.perform(post("/api/consumer-groups/g/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topico\":\"t\",\"estrategia\":\"TIMESTAMP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle", containsString("'valor' es obligatorio")));
        mvc.perform(post("/api/consumer-groups/g/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topico\":\"t\",\"estrategia\":\"LATEST\",\"valor\":5}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void resetConEstrategiaDesconocidaDevuelve400ConLosValoresAceptados() throws Exception {
        mvc.perform(post("/api/consumer-groups/g/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topico\":\"t\",\"estrategia\":\"PRIMERO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle", allOf(containsString("PRIMERO"), containsString("EARLIEST"))));
        verifyNoInteractions(servicio);
    }

    @Test
    void resetConMiembrosActivosDevuelve409() throws Exception {
        when(servicio.resetearOffsets(eq("grupo-antifraude"), any())).thenThrow(new ConflictoException(
                "El consumer group 'grupo-antifraude' tiene 3 miembro(s) activo(s)"));

        mvc.perform(post("/api/consumer-groups/grupo-antifraude/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topico\":\"payment-attempts\",\"estrategia\":\"EARLIEST\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflicto"))
                .andExpect(jsonPath("$.codigo").value(409));
    }

    @Test
    void resetValidoDevuelve200() throws Exception {
        when(servicio.resetearOffsets(eq("grupo-dashboard"), any())).thenReturn(new ResultadoResetOffsetsDTO(
                "grupo-dashboard", "interacciones-user", EstrategiaReset.OFFSET,
                List.of(new OffsetReseteadoDTO(0, 120L, 100L, false))));

        mvc.perform(post("/api/consumer-groups/grupo-dashboard/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topico\":\"interacciones-user\",\"estrategia\":\"OFFSET\",\"valor\":100,\"particiones\":[0]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estrategia").value("OFFSET"))
                .andExpect(jsonPath("$.particiones[0].offsetAnterior").value(120))
                .andExpect(jsonPath("$.particiones[0].offsetNuevo").value(100));
        verify(servicio).resetearOffsets(eq("grupo-dashboard"), argThat(r ->
                r.getValor() == 100L && r.getParticiones().equals(List.of(0))));
    }

    @Test
    void eliminarGrupoNoVacioDevuelve409EInexistente404() throws Exception {
        doThrow(new ConflictoException("tiene miembros")).when(servicio).eliminarConsumerGroup("ocupado");
        doThrow(new RecursoNoEncontradoException("no existe")).when(servicio).eliminarConsumerGroup("nada");
        mvc.perform(delete("/api/consumer-groups/ocupado")).andExpect(status().isConflict());
        mvc.perform(delete("/api/consumer-groups/nada")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/consumer-groups/vacio")).andExpect(status().isOk())
                .andExpect(content().string(containsString("eliminado")));
    }

    // ---------------------------------------------------------------- brokers

    @Test
    void configuracionDeBrokerConIdNoNumericoDevuelve400() throws Exception {
        mvc.perform(get("/api/brokers/uno/configuracion"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle", containsString("'id'")));
        verifyNoInteractions(servicio);
    }

    @Test
    void configuracionDeBrokerDevuelve200() throws Exception {
        when(servicio.obtenerConfiguracionBroker(1)).thenReturn(new ConfiguracionRecursoDTO("1", "BROKER", List.of(), Map.of()));
        mvc.perform(get("/api/brokers/1/configuracion")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("BROKER"));
    }

    // ------------------------------------------- compatibilidad y transversal

    @Test
    void crearTopicoSigueRespondiendo201Y409ComoAntes() throws Exception {
        mvc.perform(post("/api/topicos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"nuevo\",\"particiones\":3,\"replicas\":3}"))
                .andExpect(status().isCreated())
                .andExpect(content().string("Tópico 'nuevo' creado exitosamente"));

        doThrow(new ConflictoException("El tópico 'nuevo' ya existe")).when(servicio).crearTopico(any());
        mvc.perform(post("/api/topicos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"nuevo\",\"particiones\":3,\"replicas\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("El tópico 'nuevo' ya existe"));
    }

    @Test
    void clusterCaidoDevuelve503() throws Exception {
        when(servicio.obtenerCluster()).thenThrow(new KafkaNoDisponibleException("El clúster de Kafka no respondió", null));
        mvc.perform(get("/api/cluster"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Kafka no disponible"));
    }

    @Test
    void verboEquivocadoEs405YNo500() throws Exception {
        mvc.perform(patch("/api/topicos/t/configuracion")).andExpect(status().isMethodNotAllowed());
    }
}
