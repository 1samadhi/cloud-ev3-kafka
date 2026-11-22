package com.pedidos360.productos.DTO;

/** Elemento de {@code GET /api/productos/categorias}. */
public record CategoriaDTO(String slug, String etiqueta, long cantidad) {
}
