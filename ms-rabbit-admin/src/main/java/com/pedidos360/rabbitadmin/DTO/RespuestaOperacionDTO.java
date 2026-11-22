package com.pedidos360.rabbitadmin.DTO;

import java.util.Map;

/** Resultado de una operación de escritura (crear, borrar, purgar) para mostrar en la consola. */
public record RespuestaOperacionDTO(String mensaje, String recurso, String nombre,
                                    Map<String, Object> detalle, long timestamp) {

    public static RespuestaOperacionDTO de(String mensaje, String recurso, String nombre,
                                           Map<String, Object> detalle) {
        return new RespuestaOperacionDTO(mensaje, recurso, nombre, detalle, System.currentTimeMillis());
    }
}
