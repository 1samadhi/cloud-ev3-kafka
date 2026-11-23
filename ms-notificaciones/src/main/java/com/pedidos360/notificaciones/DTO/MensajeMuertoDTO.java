package com.pedidos360.notificaciones.DTO;

import java.util.List;

/**
 * Lo que se pudo extraer de un mensaje de la DLQ: sus headers x-death (quién lo
 * mató, por qué y cuántas veces) más el cuerpo original para poder diagnosticarlo.
 */
public record MensajeMuertoDTO(
        String messageId,
        String colaOrigen,
        String motivo,
        long conteo,
        String exchangeOrigen,
        List<String> routingKeys,
        String cuerpo) {
}
