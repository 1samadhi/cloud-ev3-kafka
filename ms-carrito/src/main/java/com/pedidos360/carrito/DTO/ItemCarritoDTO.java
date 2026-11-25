package com.pedidos360.carrito.DTO;

public record ItemCarritoDTO(
        String productoId,
        String nombre,
        String categoria,
        String marca,
        long precioUnitario,
        int cantidad,
        long subtotal) {
}
