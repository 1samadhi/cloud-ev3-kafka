package com.pedidos360.carrito.Model;

public enum MetodoEnvio {
    /** Despacho a domicilio: requiere dirección y comuna. */
    ENVIO,
    /** Retiro en sucursal: requiere una de las sucursales de {@link Sucursal}. */
    RETIRO
}
