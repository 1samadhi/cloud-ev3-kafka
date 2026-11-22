package com.pedidos360.rabbitadmin.Exception;

/** Ningún nodo respondió en la API HTTP de management → 503. */
public class ManagementNoDisponibleException extends RuntimeException {

    public ManagementNoDisponibleException(String mensaje) {
        super(mensaje);
    }
}
