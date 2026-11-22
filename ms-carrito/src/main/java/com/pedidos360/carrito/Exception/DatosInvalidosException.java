package com.pedidos360.carrito.Exception;

/** 400: datos de entrada que la validación declarativa no puede expresar (p. ej. sucursal desconocida). */
public class DatosInvalidosException extends RuntimeException {

    public DatosInvalidosException(String mensaje) {
        super(mensaje);
    }

    public DatosInvalidosException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
