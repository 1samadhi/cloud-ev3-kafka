package com.pedidos360.carrito.Service;

import com.pedidos360.carrito.Exception.TransicionInvalidaException;
import com.pedidos360.carrito.Model.EstadoPedido;
import com.pedidos360.carrito.Model.MetodoEnvio;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static com.pedidos360.carrito.Model.EstadoPedido.*;

/**
 * Máquina de estados del pedido (clase pura, sin Spring):
 * <pre>
 *   PENDIENTE_PAGO → PAGADO → PREPARANDO → ENVIADO → EN_CAMINO → ENTREGADO   (ENVIO)
 *   PENDIENTE_PAGO → PAGADO → PREPARANDO → LISTO_RETIRO → RETIRADO           (RETIRO)
 *   PENDIENTE_PAGO → PAGO_RECHAZADO
 * </pre>
 * Centralizarla aquí evita que el consumidor de pagos, el scheduler o un futuro endpoint admin
 * inventen saltos (p. ej. ENVIADO en un pedido de retiro o volver de ENTREGADO a PREPARANDO).
 */
public final class MaquinaEstadosPedido {

    private static final Map<EstadoPedido, EstadoPedido> FLUJO_ENVIO = new EnumMap<>(Map.of(
            PAGADO, PREPARANDO,
            PREPARANDO, ENVIADO,
            ENVIADO, EN_CAMINO,
            EN_CAMINO, ENTREGADO));

    private static final Map<EstadoPedido, EstadoPedido> FLUJO_RETIRO = new EnumMap<>(Map.of(
            PAGADO, PREPARANDO,
            PREPARANDO, LISTO_RETIRO,
            LISTO_RETIRO, RETIRADO));

    private MaquinaEstadosPedido() {
    }

    public static boolean esValida(EstadoPedido desde, EstadoPedido hacia, MetodoEnvio metodo) {
        if (desde == null || hacia == null || metodo == null) {
            return false;
        }
        // El resultado del pago es la única bifurcación; vale igual para envío y retiro.
        if (desde == PENDIENTE_PAGO) {
            return hacia == PAGADO || hacia == PAGO_RECHAZADO;
        }
        return hacia == flujo(metodo).get(desde);
    }

    public static void validar(EstadoPedido desde, EstadoPedido hacia, MetodoEnvio metodo) {
        if (!esValida(desde, hacia, metodo)) {
            throw new TransicionInvalidaException(
                    "Transición inválida " + desde + " → " + hacia + " para un pedido con " + metodo);
        }
    }

    /** Siguiente paso de la logística simulada; vacío si el pedido no está en curso o ya terminó. */
    public static Optional<EstadoPedido> siguienteLogistico(EstadoPedido desde, MetodoEnvio metodo) {
        if (desde == null || metodo == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(flujo(metodo).get(desde));
    }

    public static boolean esFinal(EstadoPedido estado) {
        return estado == ENTREGADO || estado == RETIRADO || estado == PAGO_RECHAZADO;
    }

    private static Map<EstadoPedido, EstadoPedido> flujo(MetodoEnvio metodo) {
        return metodo == MetodoEnvio.ENVIO ? FLUJO_ENVIO : FLUJO_RETIRO;
    }
}
