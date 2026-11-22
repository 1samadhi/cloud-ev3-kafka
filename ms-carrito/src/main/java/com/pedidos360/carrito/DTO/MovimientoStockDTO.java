package com.pedidos360.carrito.DTO;

import java.util.List;

/** Cuerpo de POST /api/productos/stock/reservas y /liberaciones (§7.2). */
public record MovimientoStockDTO(String idPedido, List<LineaStockDTO> items) {
}
