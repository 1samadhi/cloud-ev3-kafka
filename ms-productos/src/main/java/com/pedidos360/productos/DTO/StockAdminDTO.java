package com.pedidos360.productos.DTO;

/** Elemento de {@code GET /api/productos/admin/stock}. */
public record StockAdminDTO(String id, String nombre, int stock, NivelStock nivel) {
}
