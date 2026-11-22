package com.pedidos360.carrito.Exception;

/** 400: falta o es inválido el header X-Usuario-Id (hasta que exista Cognito, §12). */
public class UsuarioNoIdentificadoException extends RuntimeException {

    public UsuarioNoIdentificadoException(String mensaje) {
        super(mensaje);
    }

    public UsuarioNoIdentificadoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
