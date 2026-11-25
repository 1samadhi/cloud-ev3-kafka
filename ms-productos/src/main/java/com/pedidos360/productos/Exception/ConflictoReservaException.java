package com.pedidos360.productos.Exception;

/** El estado de la reserva impide la operación (p. ej. volver a reservar un pedido ya liberado) → 409. */
public class ConflictoReservaException extends RuntimeException {

    public ConflictoReservaException(String mensaje) {
        super(mensaje);
    }
}
