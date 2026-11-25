package com.pedidos360.carrito.Exception;

/** Salto no permitido por la máquina de estados del pedido (409 si llega a HTTP). */
public class TransicionInvalidaException extends RuntimeException {

    public TransicionInvalidaException(String mensaje) {
        super(mensaje);
    }

    public TransicionInvalidaException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
