package com.pedidos360.carrito.DTO;

public record ItemPedidoDTO(
        String productoId,
        String nombre,
        String categoria,
        String marca,
        long precioUnitario,
        int cantidad,
        long subtotal) {
}
