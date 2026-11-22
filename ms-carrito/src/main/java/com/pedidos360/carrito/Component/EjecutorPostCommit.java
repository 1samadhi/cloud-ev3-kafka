package com.pedidos360.carrito.Component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publica eventos/notificaciones sólo DESPUÉS de que la transacción de BD se confirma: si el
 * commit falla no se anuncia algo que no ocurrió (p. ej. "pedido pagado" con el pedido aún
 * pendiente). Y si un broker falla, el negocio ya quedó guardado.
 */
@Slf4j
@Component
public class EjecutorPostCommit {

    public void despuesDeConfirmar(Runnable accion) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ejecutarSeguro(accion);
                }
            });
        } else {
            ejecutarSeguro(accion);
        }
    }

    /** Compensación: se ejecuta sólo si la transacción termina en rollback. */
    public void siSeRevierte(Runnable compensacion) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int estado) {
                if (estado == STATUS_ROLLED_BACK) {
                    ejecutarSeguro(compensacion);
                }
            }
        });
    }

    private void ejecutarSeguro(Runnable accion) {
        try {
            accion.run();
        } catch (RuntimeException e) {
            // Los publicadores ya capturan sus errores; esto es la última red: nunca debe
            // propagarse una excepción desde un callback post-commit.
            log.error("Falló una acción posterior a la transacción: {}", e.getMessage(), e);
        }
    }
}
