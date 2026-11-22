package com.pedidos360.carrito.DTO;

import com.pedidos360.carrito.Model.EstadoPedido;
import com.pedidos360.carrito.Model.MetodoEnvio;

import java.time.Instant;
import java.util.List;

public record PedidoDTO(
        String id,
        String usuarioId,
        EstadoPedido estado,
        MetodoEnvio metodoEnvio,
        String destino,
        List<ItemPedidoDTO> items,
        long subtotal,
        long descuento,
        long costoEnvio,
        long total,
        String cupon,
        String codigoAutorizacion,
        Instant creadoEn,
        Instant actualizadoEn,
        Instant pagadoEn,
        List<HistorialEstadoDTO> historial) {
}
