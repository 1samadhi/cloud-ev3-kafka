package com.pedidos360.productos.DTO;

/** Tipos de interacción del contrato §2.3 (la lista completa, aunque aquí sólo se emita VER_PRODUCTO). */
public enum TipoEvento {
    VER_PRODUCTO,
    AGREGAR_CARRITO,
    APLICAR_CUPON,
    GESTIONAR_ENVIO,
    COMPRA
}
