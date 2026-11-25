package com.pedidos360.carrito.DTO;

import com.pedidos360.carrito.Model.MetodoEnvio;

import java.time.Instant;
import java.util.List;

/**
 * Carrito con los totales ya calculados por el servidor: la tienda sólo los muestra.
 * Son exactamente los mismos que tendrá el pedido si se compra ahora.
 */
public record CarritoDTO(
        String usuarioId,
        List<ItemCarritoDTO> items,
        int cantidadUnidades,
        String cupon,
        boolean cuponAplicado,
        String avisoCupon,
        MetodoEnvio metodoEnvio,
        String direccion,
        String comuna,
        String sucursal,
        boolean envioDefinido,
        long subtotal,
        long descuento,
        long costoEnvio,
        long total,
        long envioGratisDesde,
        long faltaParaEnvioGratis,
        Instant actualizadoEn) {
}
