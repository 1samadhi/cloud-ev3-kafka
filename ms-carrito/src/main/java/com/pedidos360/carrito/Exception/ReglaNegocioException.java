package com.pedidos360.carrito.Exception;

/** 422: la petición es válida pero el estado no lo permite (carrito vacío, faltan datos de envío...). */
public class ReglaNegocioException extends RuntimeException {

    public ReglaNegocioException(String mensaje) {
        super(mensaje);
    }

    public ReglaNegocioException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
