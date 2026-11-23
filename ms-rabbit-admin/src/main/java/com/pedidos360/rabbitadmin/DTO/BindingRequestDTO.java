package com.pedidos360.rabbitadmin.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;
import java.util.Map;

/**
 * POST y DELETE /api/rabbit/bindings.
 *
 * origen no puede ser "" (exchange por defecto): sus bindings son implícitos y
 * AMQP no permite crearlos ni borrarlos. routingKey es obligatoria pero puede
 * ser "" (lo normal en fanout, que la ignora).
 */
public record BindingRequestDTO(
        @NotBlank(message = "origen (exchange) es obligatorio")
        @Size(max = 255, message = "origen no puede superar 255 caracteres")
        String origen,

        @NotBlank(message = "destino es obligatorio")
        @Size(max = 255, message = "destino no puede superar 255 caracteres")
        String destino,

        @Pattern(regexp = "queue|exchange", message = "tipoDestino debe ser 'queue' o 'exchange'")
        String tipoDestino,

        @NotNull(message = "routingKey es obligatoria (puede ser \"\" en un exchange fanout)")
        @Size(max = 255, message = "routingKey no puede superar 255 caracteres")
        String routingKey,

        /** Para exchanges headers (x-match, etc.). */
        Map<String, Object> argumentos) {

    public BindingRequestDTO {
        tipoDestino = (tipoDestino == null || tipoDestino.isBlank()) ? "queue" : tipoDestino.trim().toLowerCase(Locale.ROOT);
        argumentos = argumentos == null ? Map.of() : argumentos;
    }

    public boolean destinoEsCola() {
        return "queue".equals(tipoDestino);
    }
}
