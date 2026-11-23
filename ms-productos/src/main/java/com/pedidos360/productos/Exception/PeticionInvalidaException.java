package com.pedidos360.productos.Exception;

/** Parámetro de la petición fuera de lo permitido (orden desconocido, header mal formado) → 400. */
public class PeticionInvalidaException extends RuntimeException {

    public PeticionInvalidaException(String mensaje) {
        super(mensaje);
    }
}
