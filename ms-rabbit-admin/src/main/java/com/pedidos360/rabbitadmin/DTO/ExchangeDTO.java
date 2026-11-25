package com.pedidos360.rabbitadmin.DTO;

import java.util.Map;

/** Exchange para la consola. nombre "" = exchange por defecto (siempre protegido). */
public record ExchangeDTO(
        String nombre,
        String vhost,
        String tipo,
        boolean durable,
        boolean autoEliminar,
        boolean interno,
        Map<String, Object> argumentos,
        boolean protegido) {
}
