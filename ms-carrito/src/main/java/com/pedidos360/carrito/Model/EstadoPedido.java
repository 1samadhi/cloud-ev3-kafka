package com.pedidos360.carrito.Model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados del pedido. Las transiciones válidas viven en {@link com.pedidos360.carrito.Service.MaquinaEstadosPedido};
 * aquí sólo se agrupan los estados para consultas (admin, scheduler).
 */
public enum EstadoPedido {
    PENDIENTE_PAGO,
    PAGO_RECHAZADO,
    PAGADO,
    PREPARANDO,
    ENVIADO,
    EN_CAMINO,
    ENTREGADO,
    LISTO_RETIRO,
    RETIRADO;

    /** Pedidos que la logística simulada todavía debe mover un paso. */
    public static Set<EstadoPedido> enCurso() {
        return EnumSet.of(PAGADO, PREPARANDO, ENVIADO, EN_CAMINO, LISTO_RETIRO);
    }

    /** "Pagados en adelante": lo único que cuenta como venta en el resumen admin. */
    public static Set<EstadoPedido> pagados() {
        return EnumSet.of(PAGADO, PREPARANDO, ENVIADO, EN_CAMINO, ENTREGADO, LISTO_RETIRO, RETIRADO);
    }
}
