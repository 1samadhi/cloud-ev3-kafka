package com.pedidos360.carrito.Component;

import com.pedidos360.carrito.Config.RabbitTopologiaProperties;
import com.pedidos360.carrito.DTO.NotificacionDTO;
import com.pedidos360.carrito.DTO.TipoNotificacion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Productor RabbitMQ de notificaciones (§9). La confirmación del broker (ack/nack) y los
 * mensajes devueltos sin ruta se registran en {@link CallbacksConfirmacionRabbit}; aquí el
 * correlationId es el propio idNotificacion para poder seguir un mensaje de punta a punta.
 *
 * Nunca lanza: si RabbitMQ está caído el pedido igual se crea/paga (dominios de falla independientes).
 */
@Slf4j
@Component
public class PublicadorNotificaciones {

    public static final String APP_ID = "ms-carrito";

    private final RabbitTemplate rabbitTemplate;
    private final RabbitTopologiaProperties topologia;
    private final Clock reloj;

    public PublicadorNotificaciones(RabbitTemplate rabbitTemplate, RabbitTopologiaProperties topologia, Clock reloj) {
        this.rabbitTemplate = rabbitTemplate;
        this.topologia = topologia;
        this.reloj = reloj;
    }

    public void publicar(TipoNotificacion tipo, String usuarioId, String titulo, String mensaje,
                         String idPedido, Map<String, String> datos) {
        NotificacionDTO notificacion = NotificacionDTO.builder()
                .idNotificacion(UUID.randomUUID().toString())
                .usuarioId(usuarioId)
                .tipo(tipo.getRoutingKey())
                .titulo(titulo)
                .mensaje(mensaje)
                .idPedido(idPedido)
                .datos(datos == null ? Map.of() : datos)
                .timestamp(reloj.millis())
                .build();
        CorrelationData correlacion = new CorrelationData(notificacion.getIdNotificacion());
        try {
            rabbitTemplate.convertAndSend(topologia.getExchange(), tipo.getRoutingKey(), notificacion, mensajeAmqp -> {
                MessageProperties propiedades = mensajeAmqp.getMessageProperties();
                // messageId permite al consumidor deduplicar; PERSISTENT sobrevive a un reinicio del broker.
                propiedades.setMessageId(notificacion.getIdNotificacion());
                propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                propiedades.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                propiedades.setAppId(APP_ID);
                propiedades.setTimestamp(new Date(notificacion.getTimestamp()));
                return mensajeAmqp;
            }, correlacion);
            log.info("Notificación enviada a RabbitMQ (esperando confirmación): exchange={} routingKey={} "
                            + "correlationId={} usuario={} pedido={}",
                    topologia.getExchange(), tipo.getRoutingKey(), correlacion.getId(), usuarioId, idPedido);
        } catch (RuntimeException e) {
            log.error("No se pudo publicar la notificación routingKey={} correlationId={} usuario={} pedido={}: {}. "
                            + "La operación de negocio continúa.",
                    tipo.getRoutingKey(), correlacion.getId(), usuarioId, idPedido, e.getMessage());
        }
    }
}
