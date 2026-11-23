package com.pedidos360.rabbitadmin.Exception;

/** Recurso amq.*, exchange por defecto o de la topología del sistema, sin ?forzar=true → 403. */
public class RecursoProtegidoException extends RuntimeException {

    public RecursoProtegidoException(String mensaje) {
        super(mensaje);
    }
}
