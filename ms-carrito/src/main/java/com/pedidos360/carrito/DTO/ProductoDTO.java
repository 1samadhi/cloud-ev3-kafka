package com.pedidos360.carrito.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Respuesta de GET /api/productos/{id} de ms-productos (§7.1/§7.2). Sólo se usa lo necesario. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductoDTO(
        String id,
        String nombre,
        String marca,
        String categoria,
        String subcategoria,
        Long precio,
        Integer stock) {
}
