package com.pedidos360.carrito.DTO;

/** Routing keys de §9. El tipo de la notificación es la propia routing key. */
public enum TipoNotificacion {
    CARRITO_PRODUCTO_AGREGADO("carrito.producto-agregado"),
    PEDIDO_CREADO("pedido.creado"),
    PEDIDO_PAGADO("pedido.pagado"),
    PEDIDO_RECHAZADO("pedido.rechazado"),
    ENVIO_ESTADO_ACTUALIZADO("envio.estado-actualizado");

    private final String routingKey;

    TipoNotificacion(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getRoutingKey() {
        return routingKey;
    }
}
