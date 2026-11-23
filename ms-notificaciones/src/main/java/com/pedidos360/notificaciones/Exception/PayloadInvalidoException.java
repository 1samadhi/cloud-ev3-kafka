package com.pedidos360.notificaciones.Exception;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/**
 * Mensaje que nunca se va a poder procesar (falta usuarioId, tipo desconocido).
 *
 * Extiende AmqpRejectAndDontRequeueException a propósito: es la señal que
 * Spring AMQP entiende como "rechazar sin reencolar", y la política de
 * reintentos de RabbitConfig la marca como NO reintentable. Resultado: el
 * mensaje va en el acto al DLX -> DLQ, sin 3 intentos inútiles.
 */
public class PayloadInvalidoException extends AmqpRejectAndDontRequeueException {

    public PayloadInvalidoException(String mensaje) {
        super(mensaje);
    }
}
