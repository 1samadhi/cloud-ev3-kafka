package com.pedidos360.carrito.Config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Nombres de la topología RabbitMQ de §9 (app.rabbit.*). */
@Data
@Component
@ConfigurationProperties(prefix = "app.rabbit")
public class RabbitTopologiaProperties {
    private String exchange = "pedidos360.notificaciones";
    private String exchangeDlx = "pedidos360.notificaciones.dlx";
    private String colaCliente = "notificaciones.cliente";
    private String colaAuditoria = "notificaciones.auditoria";
    private String colaDlq = "notificaciones.cliente.dlq";
}
