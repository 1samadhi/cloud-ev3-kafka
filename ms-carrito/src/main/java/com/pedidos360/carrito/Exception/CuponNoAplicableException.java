package com.pedidos360.carrito.Exception;

/** 422: el cupón existe pero no cumple sus condiciones (mínimo de compra). */
public class CuponNoAplicableException extends ReglaNegocioException {

    public CuponNoAplicableException(String mensaje) {
        super(mensaje);
    }
}
