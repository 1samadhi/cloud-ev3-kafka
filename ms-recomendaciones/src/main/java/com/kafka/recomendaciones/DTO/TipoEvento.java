package com.kafka.recomendaciones.DTO;

/**
 * Tipos de evento de interacciones-user (§2.3).
 *
 * Solo sabe si el evento necesita productoId: el peso de cada tipo es regla del
 * motor de recomendaciones, no del contrato del mensaje.
 */
public enum TipoEvento {
    VER_PRODUCTO(true),
    AGREGAR_CARRITO(true),
    APLICAR_CUPON(false),
    GESTIONAR_ENVIO(false),
    COMPRA(true);

    private final boolean requiereProducto;

    TipoEvento(boolean requiereProducto) {
        this.requiereProducto = requiereProducto;
    }

    public boolean requiereProducto() {
        return requiereProducto;
    }
}
