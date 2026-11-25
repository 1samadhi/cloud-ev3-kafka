package com.pedidos360.rabbitadmin.Exception;

/** El estado actual impide la operación (cola con mensajes y siVacia=true, broker rechazó el borrado) → 409. */
public class ConflictoException extends RuntimeException {

    public ConflictoException(String mensaje) {
        super(mensaje);
    }
}
