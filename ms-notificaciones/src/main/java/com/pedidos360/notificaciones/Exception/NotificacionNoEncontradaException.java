package com.pedidos360.notificaciones.Exception;

public class NotificacionNoEncontradaException extends RuntimeException {

    public NotificacionNoEncontradaException(String id) {
        super("No existe la notificación '" + id + "'");
    }
}
