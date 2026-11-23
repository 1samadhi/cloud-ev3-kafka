package com.pedidos360.carrito.Exception;

/** 503: ms-productos no responde. Se distingue del 404 para que la tienda pueda reintentar. */
public class ServicioNoDisponibleException extends RuntimeException {

    public ServicioNoDisponibleException(String mensaje) {
        super(mensaje);
    }

    public ServicioNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
