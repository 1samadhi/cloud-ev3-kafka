package com.pedidos360.notificaciones.Model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Las routing keys que existen (§9). Un tipo fuera de esta lista es un mensaje
 * que la tienda no sabría mostrar: se rechaza a la DLQ en vez de guardarlo.
 */
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

    public static Optional<TipoNotificacion> desdeRoutingKey(String valor) {
        if (valor == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(t -> t.routingKey.equals(valor)).findFirst();
    }
}
