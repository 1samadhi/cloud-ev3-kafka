package com.pedidos360.rabbitadmin.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursos "del sistema" que no se borran sin ?forzar=true (app.rabbit.proteccion.*).
 *
 * El prefijo amq. NO es configurable (ver ProteccionRecursos): lo reserva el
 * propio RabbitMQ, no es una decisión de este proyecto.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.rabbit.proteccion")
public class ProteccionRecursosProperties {

    /**
     * Topología de notificaciones (PLAN-INTEGRACION-EV3 §9). Si alguien borra
     * notificaciones.cliente desde la consola, ms-carrito sigue publicando con
     * mandatory=true y cada mensaje vuelve como "sin ruta": las notificaciones
     * se pierden hasta que un servicio reinicie y la redeclare. Por eso exige
     * confirmación explícita. Los defaults son los del contrato para que la
     * protección exista aunque falte la sección en el yaml.
     */
    private List<String> sistema = new ArrayList<>(List.of(
            "pedidos360.notificaciones",
            "pedidos360.notificaciones.dlx",
            "notificaciones.cliente",
            "notificaciones.auditoria",
            "notificaciones.cliente.dlq"));
}
