package com.pedidos360.rabbitadmin;

import com.pedidos360.rabbitadmin.Component.ClienteManagementRabbit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contexto completo arranca SIN RabbitMQ: la conexión AMQP es perezosa y la
 * API de management sólo se consulta cuando llega una petición.
 */
@SpringBootTest
class MsRabbitAdminApplicationTests {

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ClienteManagementRabbit clienteManagement;

    @Test
    void contextoArrancaSinBrokerConRabbitAdminYClienteDeManagement() {
        // RabbitAdmin lo crea la autoconfiguración de Boot: es la implementación
        // de AmqpAdmin con la que se declara y borra.
        assertThat(amqpAdmin).isInstanceOf(RabbitAdmin.class);
        // La primera URL de la lista (yaml de test) es la preferida al arrancar.
        assertThat(clienteManagement.urlActiva()).isEqualTo("http://rabbit-test-1:15672");
    }
}
