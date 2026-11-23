package com.pedidos360.carrito.DTO;

/** Ítem del cuerpo de /stock/reservas y /stock/liberaciones de ms-productos. */
public record LineaStockDTO(String productoId, int cantidad) {
}
