package com.pedidos360.notificaciones.DTO;

import com.pedidos360.notificaciones.Model.Notificacion;

import java.time.Instant;
import java.util.Map;

/** Lo que ve el navegador, tanto en el listado REST como en el evento SSE. */
public record NotificacionRespuestaDTO(
        String id,
        String usuarioId,
        String tipo,
        String titulo,
        String mensaje,
        String idPedido,
        Map<String, Object> datos,
        boolean leida,
        Instant creadaEn) {

    public static NotificacionRespuestaDTO desde(Notificacion n) {
        return new NotificacionRespuestaDTO(n.getId(), n.getUsuarioId(), n.getTipo(), n.getTitulo(),
                n.getMensaje(), n.getIdPedido(), n.getDatos(), n.isLeida(), n.getCreadaEn());
    }
}
