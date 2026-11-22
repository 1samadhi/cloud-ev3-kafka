package com.pedidos360.carrito.Service;

import com.pedidos360.carrito.DTO.CarritoDTO;
import com.pedidos360.carrito.DTO.HistorialEstadoDTO;
import com.pedidos360.carrito.DTO.ItemCarritoDTO;
import com.pedidos360.carrito.DTO.ItemPedidoDTO;
import com.pedidos360.carrito.DTO.PedidoDTO;
import com.pedidos360.carrito.Model.Carrito;
import com.pedidos360.carrito.Model.MetodoEnvio;
import com.pedidos360.carrito.Model.Pedido;

/** Entidad → DTO. Se llama dentro de la transacción del servicio (open-in-view está apagado). */
public final class MapeadorDTO {

    private MapeadorDTO() {
    }

    public static CarritoDTO carrito(Carrito c, CalculadoraTotales.Totales t) {
        var items = c.getItems().stream()
                .map(i -> new ItemCarritoDTO(i.getProductoId(), i.getNombre(), i.getCategoria(), i.getMarca(),
                        i.getPrecioUnitario(), i.getCantidad(), i.getPrecioUnitario() * i.getCantidad()))
                .toList();
        int unidades = c.getItems().stream().mapToInt(i -> i.getCantidad()).sum();
        boolean envioDefinido = c.getMetodoEnvio() == MetodoEnvio.ENVIO
                ? noVacio(c.getDireccion()) && noVacio(c.getComuna())
                : c.getMetodoEnvio() == MetodoEnvio.RETIRO && noVacio(c.getSucursal());
        return new CarritoDTO(c.getUsuarioId(), items, unidades, c.getCupon(), t.cuponAplicado(), t.avisoCupon(),
                c.getMetodoEnvio(), c.getDireccion(), c.getComuna(), c.getSucursal(), envioDefinido,
                t.subtotal(), t.descuento(), t.costoEnvio(), t.total(),
                CalculadoraTotales.UMBRAL_ENVIO_GRATIS, t.faltaParaEnvioGratis(), c.getActualizadoEn());
    }

    public static PedidoDTO pedido(Pedido p) {
        var items = p.getItems().stream()
                .map(i -> new ItemPedidoDTO(i.getProductoId(), i.getNombre(), i.getCategoria(), i.getMarca(),
                        i.getPrecioUnitario(), i.getCantidad(), i.getPrecioUnitario() * i.getCantidad()))
                .toList();
        var historial = p.getHistorial().stream()
                .map(h -> new HistorialEstadoDTO(h.getEstado(), h.getInstante()))
                .toList();
        return new PedidoDTO(p.getId(), p.getUsuarioId(), p.getEstado(), p.getMetodoEnvio(), p.getDestino(), items,
                p.getSubtotal(), p.getDescuento(), p.getCostoEnvio(), p.getTotal(), p.getCupon(),
                p.getCodigoAutorizacion(), p.getCreadoEn(), p.getActualizadoEn(), p.getPagadoEn(), historial);
    }

    private static boolean noVacio(String s) {
        return s != null && !s.isBlank();
    }
}
