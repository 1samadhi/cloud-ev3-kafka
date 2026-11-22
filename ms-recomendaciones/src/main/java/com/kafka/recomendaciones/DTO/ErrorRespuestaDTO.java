package com.kafka.recomendaciones.DTO;

/** Forma unica de error de la API (§3.5): { error, detalle, timestamp }. */
public record ErrorRespuestaDTO(String error, String detalle, long timestamp) {

    public static ErrorRespuestaDTO de(String error, String detalle) {
        return new ErrorRespuestaDTO(error, detalle, System.currentTimeMillis());
    }
}
