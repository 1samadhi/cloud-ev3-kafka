package com.pedidos360.productos.DTO;

/** Un producto que no alcanzó en una reserva; {@code disponible} = stock actual (0 si no existe). */
public record FaltanteDTO(String productoId, int disponible) {
}
