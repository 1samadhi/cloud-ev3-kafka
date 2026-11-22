package com.pedidos360.rabbitadmin.Exception;

/** La cola, exchange o binding no existe → 404. */
public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
