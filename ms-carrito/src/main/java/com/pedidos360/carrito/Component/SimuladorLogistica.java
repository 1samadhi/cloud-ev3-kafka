package com.pedidos360.carrito.Component;

import com.pedidos360.carrito.Service.ServicioPedidos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Logística SIMULADA (§8): no hay un sistema de despacho real, así que cada tick avanza un paso a
 * los pedidos en curso (PAGADO → PREPARANDO → ENVIADO → …) y ServicioPedidos publica
 * envio.estado-actualizado en RabbitMQ. Sirve para demostrar las notificaciones en vivo.
 *
 * Se apaga con app.envios.avance-habilitado=false (los tests lo apagan para no mover pedidos solos).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.envios.avance-habilitado", havingValue = "true", matchIfMissing = true)
public class SimuladorLogistica {

    private final ServicioPedidos servicioPedidos;

    @Scheduled(fixedDelayString = "${app.envios.avance-ms:20000}", initialDelayString = "${app.envios.avance-ms:20000}")
    public void avanzar() {
        for (String id : servicioPedidos.idsEnCurso()) {
            try {
                // Una transacción por pedido (en el servicio): si uno falla, los demás avanzan igual.
                servicioPedidos.avanzarPedido(id);
            } catch (RuntimeException e) {
                // Típicamente un conflicto @Version con el consumidor de pagos: se reintenta en el próximo tick.
                log.warn("No se pudo avanzar el pedido {} en este tick: {}", id, e.getMessage());
            }
        }
    }
}
