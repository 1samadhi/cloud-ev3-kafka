package com.pedidos360.carrito.Exception;

/** Mensaje Kafka que no pasa la validación: no se reintenta, va directo al DLT. */
public class PayloadInvalidoException extends RuntimeException {

    public PayloadInvalidoException(String mensaje) {
        super(mensaje);
    }

    public PayloadInvalidoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
