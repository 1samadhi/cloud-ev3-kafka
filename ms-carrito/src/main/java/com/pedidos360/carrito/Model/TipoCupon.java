package com.pedidos360.carrito.Model;

public enum TipoCupon {
    /** valor = porcentaje sobre el subtotal (10 = 10 %). */
    PORCENTAJE,
    /** valor = monto fijo en CLP, nunca mayor que el subtotal. */
    MONTO,
    /**
     * Descuenta el costo de despacho. §8 sólo nombra PORCENTAJE|MONTO, pero ENVIOGRATIS
     * "descuenta el envío", que no es expresable con esos dos sin acoplar el cupón al costo
     * de envío vigente: por eso es un tipo propio.
     */
    ENVIO_GRATIS
}
