package com.pedidos360.notificaciones.Exception;

public class UsuarioNoIdentificadoException extends RuntimeException {

    public UsuarioNoIdentificadoException() {
        super("Falta el usuario: envíe ?usuarioId= o el header X-Usuario-Id");
    }
}
