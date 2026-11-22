package com.pedidos360.notificaciones.Component;

import com.pedidos360.notificaciones.DTO.NotificacionDTO;
import com.pedidos360.notificaciones.Exception.PayloadInvalidoException;
import com.pedidos360.notificaciones.Model.TipoNotificacion;
import com.pedidos360.notificaciones.Service.ServicioNotificaciones;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consumidor de la cola notificaciones.cliente.
 *
 * Sólo mensajería: loguea el contexto RabbitMQ, valida y delega en
 * ServicioNotificaciones. Cero reglas de negocio acá (§3.3).
 *
 * Qué pasa con cada tipo de fallo:
 *   - payload inválido -> PayloadInvalidoException (AmqpRejectAndDontRequeue)
 *       -> sin reintentos -> DLX -> DLQ.
 *   - error transitorio del servicio (base caída) -> se propaga -> 3 intentos
 *       con backoff (RabbitConfig) -> si se agotan, DLX -> DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumidorNotificaciones {

    private final Validator validator;
    private final ServicioNotificaciones servicioNotificaciones;

    @RabbitListener(id = "consumidor-notificaciones-cliente",
            queues = "#{@topologiaRabbitProperties.colaCliente}")
    public void recibir(@Payload NotificacionDTO notificacion, Message mensaje) {
        MessageProperties props = mensaje.getMessageProperties();
        log.info("Mensaje recibido: cola={} routingKey={} messageId={} reentregado={} tipo={} usuarioId={}",
                props.getConsumerQueue(), props.getReceivedRoutingKey(), props.getMessageId(),
                props.isRedelivered(),
                notificacion == null ? null : notificacion.getTipo(),
                notificacion == null ? null : notificacion.getUsuarioId());

        validar(notificacion, props);
        servicioNotificaciones.procesar(notificacion);
    }

    void validar(NotificacionDTO notificacion, MessageProperties props) {
        if (notificacion == null) {
            throw new PayloadInvalidoException("Mensaje sin cuerpo: messageId=" + props.getMessageId());
        }

        Set<ConstraintViolation<NotificacionDTO>> violaciones = validator.validate(notificacion);
        if (!violaciones.isEmpty()) {
            String detalle = violaciones.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining("; "));
            throw new PayloadInvalidoException("NotificacionDTO inválido (messageId="
                    + props.getMessageId() + "): " + detalle);
        }

        if (TipoNotificacion.desdeRoutingKey(notificacion.getTipo()).isEmpty()) {
            throw new PayloadInvalidoException("Tipo de notificación desconocido '" + notificacion.getTipo()
                    + "' (messageId=" + props.getMessageId() + ")");
        }

        // No se rechaza: el contenido es válido, pero indica un productor que
        // publica con una routing key distinta al tipo que declara.
        String routingKey = props.getReceivedRoutingKey();
        if (routingKey != null && !routingKey.equals(notificacion.getTipo())) {
            log.warn("El tipo '{}' no coincide con la routing key '{}' (messageId={})",
                    notificacion.getTipo(), routingKey, props.getMessageId());
        }
    }
}
