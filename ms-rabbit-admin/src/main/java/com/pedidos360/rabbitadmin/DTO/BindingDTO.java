package com.pedidos360.rabbitadmin.DTO;

import java.util.Map;

/** Binding origen (exchange) → destino (cola o exchange) con su routing key. */
public record BindingDTO(
        String origen,
        String destino,
        String tipoDestino,
        String routingKey,
        Map<String, Object> argumentos,
        String propertiesKey,
        boolean protegido) {
}
