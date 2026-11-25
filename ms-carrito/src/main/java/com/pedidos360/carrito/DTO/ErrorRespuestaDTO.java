package com.pedidos360.carrito.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Forma única de error de §3.5: { error, detalle, timestamp } (+ faltantes en el 409 de stock). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorRespuestaDTO(String error, String detalle, long timestamp, List<FaltanteDTO> faltantes) {

    public static ErrorRespuestaDTO de(String error, String detalle) {
        return new ErrorRespuestaDTO(error, detalle, System.currentTimeMillis(), null);
    }
}
