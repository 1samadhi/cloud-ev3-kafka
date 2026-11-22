package com.pedidos360.carrito.Service;

/** Qué hizo ServicioPedidos con un mensaje de pagos-resultado (útil para logs y tests). */
public enum ResultadoProcesamiento {
    PAGADO,
    RECHAZADO,
    /** El pedido ya estaba resuelto: mensaje repetido (Kafka garantiza at-least-once). */
    IGNORADO_DUPLICADO,
    /** idOrden no es un pedido de la tienda (p. ej. un pago hecho desde la consola). */
    IGNORADO_DESCONOCIDO
}
