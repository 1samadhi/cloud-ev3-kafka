package com.pedidos360.productos.DTO;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Forma única de error de la API (§3.5): {@code { error, detalle, timestamp }}.
 * {@code faltantes} sólo aparece en el 409 de reservas (§7.2) para que ms-carrito pueda
 * propagar al cliente qué productos no alcanzaron.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDTO(String error, String detalle, List<FaltanteDTO> faltantes, long timestamp) {

    public static ErrorDTO de(String error, String detalle) {
        return new ErrorDTO(error, detalle, null, System.currentTimeMillis());
    }
}
