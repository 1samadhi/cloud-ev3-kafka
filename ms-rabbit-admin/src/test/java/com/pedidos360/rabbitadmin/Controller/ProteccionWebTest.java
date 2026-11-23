package com.pedidos360.rabbitadmin.Controller;

import com.pedidos360.rabbitadmin.Component.ClienteManagementRabbit;
import com.pedidos360.rabbitadmin.Component.ProteccionRecursos;
import com.pedidos360.rabbitadmin.Config.ProteccionRecursosProperties;
import com.pedidos360.rabbitadmin.Service.ServicioRabbitAdmin;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * De punta a punta por HTTP con el servicio y la protección REALES (sólo
 * AmqpAdmin y la API de management mockeados): un DELETE sobre amq.* o la
 * topología del sistema responde 403 y NUNCA llega al broker.
 */
@WebMvcTest(RabbitAdminController.class)
@Import({ServicioRabbitAdmin.class, ProteccionRecursos.class})
@EnableConfigurationProperties(ProteccionRecursosProperties.class)
class ProteccionWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AmqpAdmin amqpAdmin;

    @MockitoBean
    private ClienteManagementRabbit management;

    @Test
    void borrarExchangeAmqSinForzarEs403YNoTocaElBroker() throws Exception {
        mvc.perform(delete("/api/rabbit/exchanges/amq.topic"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detalle").value(containsString("amq.")));
        verifyNoInteractions(amqpAdmin, management);
    }

    @Test
    void borrarColaAmqGenSinForzarEs403() throws Exception {
        mvc.perform(delete("/api/rabbit/colas/amq.gen-JzTY20BRgKO"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(amqpAdmin);
    }

    @Test
    void borrarColaDelSistemaSinForzarEs403YConForzarSeBorra() throws Exception {
        mvc.perform(delete("/api/rabbit/colas/notificaciones.cliente"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detalle").value(containsString("forzar=true")));
        verifyNoInteractions(amqpAdmin);

        when(amqpAdmin.getQueueInfo("notificaciones.cliente"))
                .thenReturn(new QueueInformation("notificaciones.cliente", 0, 1));
        when(amqpAdmin.deleteQueue("notificaciones.cliente")).thenReturn(true);
        mvc.perform(delete("/api/rabbit/colas/notificaciones.cliente").param("forzar", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detalle.forzado").value(true));
        verify(amqpAdmin).deleteQueue("notificaciones.cliente");
    }

    @Test
    void borrarColaComunNoNecesitaForzar() throws Exception {
        when(amqpAdmin.getQueueInfo("prueba.classic")).thenReturn(new QueueInformation("prueba.classic", 0, 0));
        when(amqpAdmin.deleteQueue("prueba.classic")).thenReturn(true);

        mvc.perform(delete("/api/rabbit/colas/prueba.classic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value("Cola borrada"));
    }
}
