package com.pedidos360.rabbitadmin.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Conexión a la API HTTP de management de RabbitMQ (app.rabbit.management.*).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.rabbit.management")
public class RabbitManagementProperties {

    /**
     * URLs base de la API de management, una por nodo, en orden de preferencia.
     * Es una LISTA a propósito: la demo del clúster consiste en apagar un nodo
     * y ver en GET /api/rabbit/cluster que quedó caído. Si sólo se conociera la
     * URL de rabbitmq1 y se apagara justo ese, el panel quedaría ciego. Todos
     * los nodos sirven la misma vista del clúster, así que cualquiera responde.
     * Por variable de entorno: RABBITMQ_MGMT_URL separada por comas.
     */
    private List<String> urls = new ArrayList<>(List.of("http://localhost:15672"));

    /** Usuario con tag administrator/monitoring (el mismo de AMQP en el compose). */
    private String usuario = "pedidos360";

    private String clave = "pedidos360";

    /** Virtual host administrado; el mismo que spring.rabbitmq.virtual-host. */
    private String vhost = "/";

    /**
     * Timeout de conexión corto: un nodo apagado no responde (el contenedor no
     * existe) y no tiene sentido esperar los ~2 min del default del sistema
     * operativo antes de probar el siguiente nodo.
     */
    private Duration timeoutConexion = Duration.ofSeconds(2);

    /** /api/nodes en un nodo cargado puede tardar; 5 s sobra para 3 nodos. */
    private Duration timeoutLectura = Duration.ofSeconds(5);
}
