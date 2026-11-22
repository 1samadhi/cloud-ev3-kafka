package com.pedidos360.notificaciones.DTO;

/** Forma única de error de todos los servicios (PLAN-INTEGRACION-EV3 §3.5). */
public record ErrorRespuestaDTO(String error, String detalle, long timestamp) {

    public static ErrorRespuestaDTO de(String error, String detalle) {
        return new ErrorRespuestaDTO(error, detalle, System.currentTimeMillis());
    }
}
