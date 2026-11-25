package com.pedidos360.rabbitadmin.Exception;

/** Se intentó crear algo que ya existe → 409. */
public class RecursoYaExisteException extends RuntimeException {

    public RecursoYaExisteException(String mensaje) {
        super(mensaje);
    }
}
