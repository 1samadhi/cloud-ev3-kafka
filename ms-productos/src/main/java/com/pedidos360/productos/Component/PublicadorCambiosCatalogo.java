package com.pedidos360.productos.Component;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.pedidos360.productos.Service.CambioCatalogoEvento;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publica los snapshots de un cambio de stock/precio SÓLO si la transacción hizo commit.
 *
 * Si se publicara dentro de la transacción, una reserva que termina en rollback (409)
 * dejaría en el tópico compactado un stock que nunca existió, y ms-recomendaciones lo
 * tomaría como verdad. fallbackExecution=true: si algún día se llama sin transacción, se
 * publica igual en vez de perder el cambio en silencio.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicadorCambiosCatalogo {

    private final ProductorCatalogo productorCatalogo;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alConfirmarse(CambioCatalogoEvento evento) {
        log.debug("Publicando {} snapshot(s) tras commit de {}", evento.snapshots().size(), evento.motivo());
        evento.snapshots().forEach(productorCatalogo::publicar);
    }
}
