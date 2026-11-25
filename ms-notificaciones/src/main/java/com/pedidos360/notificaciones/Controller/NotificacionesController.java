package com.pedidos360.notificaciones.Controller;

import com.pedidos360.notificaciones.Component.UsuarioActual;
import com.pedidos360.notificaciones.DTO.MensajeFallidoDTO;
import com.pedidos360.notificaciones.DTO.NotificacionRespuestaDTO;
import com.pedidos360.notificaciones.Service.EmisorSse;
import com.pedidos360.notificaciones.Service.ServicioMensajesFallidos;
import com.pedidos360.notificaciones.Service.ServicioNotificaciones;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notificaciones")
@RequiredArgsConstructor
public class NotificacionesController {

    private static final String HEADER_USUARIO = "X-Usuario-Id";

    private final ServicioNotificaciones servicioNotificaciones;
    private final ServicioMensajesFallidos servicioMensajesFallidos;
    private final EmisorSse emisorSse;
    private final UsuarioActual usuarioActual;

    /** Las 50 más recientes del usuario, la más nueva primero. */
    @GetMapping
    public List<NotificacionRespuestaDTO> listar(
            @RequestParam(required = false) String usuarioId,
            @RequestHeader(name = HEADER_USUARIO, required = false) String usuarioHeader) {
        return servicioNotificaciones.listar(usuarioActual.resolver(usuarioId, usuarioHeader));
    }

    /**
     * Stream SSE. El usuario va por query porque EventSource no permite headers.
     * Eventos: "conectado" al abrir, "notificacion" por cada una, y un comentario
     * de heartbeat cada 20 s.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) String usuarioId,
            @RequestHeader(name = HEADER_USUARIO, required = false) String usuarioHeader) {
        return emisorSse.suscribir(usuarioActual.resolver(usuarioId, usuarioHeader));
    }

    @PatchMapping("/{id}/leida")
    public NotificacionRespuestaDTO marcarLeida(@PathVariable String id) {
        return servicioNotificaciones.marcarLeida(id);
    }

    @PostMapping("/leidas")
    public Map<String, Object> marcarTodasLeidas(
            @RequestParam(required = false) String usuarioId,
            @RequestHeader(name = HEADER_USUARIO, required = false) String usuarioHeader) {
        String usuario = usuarioActual.resolver(usuarioId, usuarioHeader);
        int actualizadas = servicioNotificaciones.marcarTodasLeidas(usuario);
        return Map.of("usuarioId", usuario, "actualizadas", actualizadas);
    }

    /** Admin: lo que terminó en la DLQ, con el motivo que dio RabbitMQ. */
    @GetMapping("/fallidas")
    public List<MensajeFallidoDTO> fallidas() {
        return servicioMensajesFallidos.listar();
    }
}
