package com.pedidos360.carrito.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FaltanteDTO(String productoId, Integer disponible) {
}
