package com.pedidos360.notificaciones.DTO;

import com.pedidos360.notificaciones.Model.MensajeFallido;

import java.time.Instant;

/** Vista admin de un mensaje que terminó en la DLQ (GET /api/notificaciones/fallidas). */
public record MensajeFallidoDTO(
        Long id,
        String messageId,
        String colaOrigen,
        String exchangeOrigen,
        String routingKeys,
        String motivo,
        long conteo,
        String cuerpo,
        Instant recibidoEn) {

    public static MensajeFallidoDTO desde(MensajeFallido m) {
        return new MensajeFallidoDTO(m.getId(), m.getMessageId(), m.getColaOrigen(), m.getExchangeOrigen(),
                m.getRoutingKeys(), m.getMotivo(), m.getConteo(), m.getCuerpo(), m.getRecibidoEn());
    }
}
