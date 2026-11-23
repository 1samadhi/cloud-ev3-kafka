package com.pedidos360.carrito.Component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Garantías del productor (§9):
 * - publisher confirms (correlated): el broker confirma cada mensaje; un nack significa que el
 *   mensaje NO quedó a salvo (p. ej. cola quorum llena con reject-publish).
 * - publisher returns + mandatory: un mensaje que el exchange no pudo rutear a ninguna cola se
 *   devuelve en vez de perderse en silencio.
 */
@Slf4j
@Component
public class CallbacksConfirmacionRabbit implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    @Override
    public void confirm(CorrelationData correlacion, boolean ack, String causa) {
        String id = correlacion == null ? "(sin correlationId)" : correlacion.getId();
        if (ack) {
            log.info("RabbitMQ confirmó (ack) la notificación correlationId={}", id);
        } else {
            log.error("RabbitMQ rechazó (nack) la notificación correlationId={} causa={}", id, causa);
        }
    }

    @Override
    public void returnedMessage(ReturnedMessage devuelto) {
        log.error("Notificación devuelta sin ruta: exchange={} routingKey={} código={} motivo={} messageId={}",
                devuelto.getExchange(), devuelto.getRoutingKey(), devuelto.getReplyCode(), devuelto.getReplyText(),
                devuelto.getMessage().getMessageProperties().getMessageId());
    }
}
