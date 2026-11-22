package com.pedidos360.notificaciones.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Nombres de la topología RabbitMQ (PLAN-INTEGRACION-EV3 §9).
 *
 * Los defaults son los del contrato a propósito: aunque falte la sección en el
 * yaml, el servicio declara EXACTAMENTE lo mismo que ms-carrito. Es @Component
 * (y no sólo @ConfigurationProperties) para tener un nombre de bean fijo,
 * "topologiaRabbitProperties", que los @RabbitListener usan por SpEL: así no hay
 * ningún nombre de cola literal repartido por el código.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rabbit.topologia")
public class TopologiaRabbitProperties {

    /** Exchange topic por donde entran todas las notificaciones. */
    private String exchange = "pedidos360.notificaciones";

    /** Exchange fanout al que la cola cliente manda sus mensajes muertos. */
    private String exchangeDlx = "pedidos360.notificaciones.dlx";

    private String colaCliente = "notificaciones.cliente";

    private String colaAuditoria = "notificaciones.auditoria";

    private String colaDlq = "notificaciones.cliente.dlq";

    /**
     * x-delivery-limit de la cola cliente: tras 5 entregas fallidas el broker
     * manda el mensaje al DLX aunque el consumidor muera antes de rechazarlo.
     */
    private int limiteEntregas = 5;
}
