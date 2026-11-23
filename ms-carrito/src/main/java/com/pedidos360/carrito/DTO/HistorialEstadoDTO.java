package com.pedidos360.carrito.DTO;

import com.pedidos360.carrito.Model.EstadoPedido;

import java.time.Instant;

public record HistorialEstadoDTO(EstadoPedido estado, Instant instante) {
}
