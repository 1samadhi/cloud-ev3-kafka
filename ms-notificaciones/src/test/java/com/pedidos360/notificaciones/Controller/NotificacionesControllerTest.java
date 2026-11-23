package com.pedidos360.notificaciones.Controller;

import com.pedidos360.notificaciones.Component.UsuarioActual;
import com.pedidos360.notificaciones.DTO.NotificacionRespuestaDTO;
import com.pedidos360.notificaciones.Exception.NotificacionNoEncontradaException;
import com.pedidos360.notificaciones.Service.EmisorSse;
import com.pedidos360.notificaciones.Service.ServicioMensajesFallidos;
import com.pedidos360.notificaciones.Service.ServicioNotificaciones;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificacionesController.class)
@Import(UsuarioActual.class)
class NotificacionesControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServicioNotificaciones servicioNotificaciones;

    @MockitoBean
    private ServicioMensajesFallidos servicioMensajesFallidos;

    @MockitoBean
    private EmisorSse emisorSse;

    @Test
    void listarSinUsuarioResponde400ConFormatoDeError() throws Exception {
        mvc.perform(get("/api/notificaciones"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Petición inválida"))
                .andExpect(jsonPath("$.detalle").exists())
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void listarAceptaQueryOHeader() throws Exception {
        NotificacionRespuestaDTO n = new NotificacionRespuestaDTO("id-1", "u-1", "pedido.pagado", "Pagado",
                "ok", "P-1", Map.of(), false, Instant.parse("2026-11-22T12:00:00Z"));
        when(servicioNotificaciones.listar("u-1")).thenReturn(List.of(n));

        mvc.perform(get("/api/notificaciones").param("usuarioId", "u-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("id-1"))
                .andExpect(jsonPath("$[0].leida").value(false));
        mvc.perform(get("/api/notificaciones").header("X-Usuario-Id", "u-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tipo").value("pedido.pagado"));
    }

    @Test
    void marcarLeidaInexistenteResponde404() throws Exception {
        when(servicioNotificaciones.marcarLeida("no-existe")).thenThrow(new NotificacionNoEncontradaException("no-existe"));

        mvc.perform(patch("/api/notificaciones/no-existe/leida"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detalle").value("No existe la notificación 'no-existe'"));
    }

    @Test
    void marcarTodasLeidasDevuelveLaCantidad() throws Exception {
        when(servicioNotificaciones.marcarTodasLeidas("u-1")).thenReturn(3);

        mvc.perform(post("/api/notificaciones/leidas").param("usuarioId", "u-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualizadas").value(3));
    }

    @Test
    void streamSinUsuarioResponde400() throws Exception {
        mvc.perform(get("/api/notificaciones/stream"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corsPermitePatchDesdeLaTienda() throws Exception {
        mvc.perform(options("/api/notificaciones/x/leida")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "X-Usuario-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"));
    }
}
