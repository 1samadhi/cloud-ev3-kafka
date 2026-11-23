package com.pedidos360.rabbitadmin.Component;

import com.pedidos360.rabbitadmin.Config.RabbitManagementConfig;
import com.pedidos360.rabbitadmin.Config.RabbitManagementProperties;
import com.pedidos360.rabbitadmin.Exception.ManagementNoDisponibleException;
import com.pedidos360.rabbitadmin.Model.BindingApi;
import com.pedidos360.rabbitadmin.Model.ColaApi;
import com.pedidos360.rabbitadmin.Model.ExchangeApi;
import com.pedidos360.rabbitadmin.Model.NodoApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * Parsing de respuestas REALES de la API de management de RabbitMQ 3.13
 * (src/test/resources/management/*.json, con todos los campos que la API
 * manda aunque no se usen) y failover entre nodos, sin ningún broker.
 */
class ClienteManagementRabbitTest {

    private static final String NODO1 = "http://rabbit-test-1:15672";
    private static final String NODO2 = "http://rabbit-test-2:15672";
    private static final String BASIC = "Basic " + Base64.getEncoder()
            .encodeToString("pedidos360:pedidos360".getBytes(StandardCharsets.UTF_8));

    private MockRestServiceServer servidor;
    private ClienteManagementRabbit cliente;

    @BeforeEach
    void preparar() {
        crearCliente("/", NODO1, NODO2 + "/");
    }

    private void crearCliente(String vhost, String... urls) {
        RabbitManagementProperties props = new RabbitManagementProperties();
        props.setUrls(List.of(urls));
        props.setUsuario("pedidos360");
        props.setClave("pedidos360");
        props.setVhost(vhost);
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new ClienteManagementRabbit(RabbitManagementConfig.configurarAutenticacion(builder, props).build(), props);
    }

    private static String json(String archivo) {
        try {
            return new ClassPathResource("management/" + archivo).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ResponseCreator conexionRechazada() {
        return request -> {
            throw new ConnectException("Connection refused");
        };
    }

    @Test
    void listarColasParseaQuorumClassicYColaSinEstadisticas() {
        servidor.expect(requestTo(NODO1 + "/api/queues/%2F"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withSuccess(json("queues.json"), MediaType.APPLICATION_JSON));

        List<ColaApi> colas = cliente.listarColas();

        servidor.verify();
        assertThat(colas).hasSize(4);

        ColaApi colaCliente = colas.get(0);
        assertThat(colaCliente.name()).isEqualTo("notificaciones.cliente");
        assertThat(colaCliente.type()).isEqualTo("quorum");
        assertThat(colaCliente.durable()).isTrue();
        assertThat(colaCliente.messages()).isEqualTo(3L);
        assertThat(colaCliente.messagesReady()).isEqualTo(2L);
        assertThat(colaCliente.messagesUnacknowledged()).isEqualTo(1L);
        assertThat(colaCliente.consumers()).isEqualTo(1);
        assertThat(colaCliente.leader()).isEqualTo("rabbit@rabbitmq2");
        assertThat(colaCliente.members()).containsExactlyInAnyOrder("rabbit@rabbitmq1", "rabbit@rabbitmq2", "rabbit@rabbitmq3");
        assertThat(colaCliente.arguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-delivery-limit", 5)
                .containsEntry("x-dead-letter-exchange", "pedidos360.notificaciones.dlx")
                .containsEntry("x-overflow", "reject-publish");

        // Quorum con una réplica caída: online tiene 2 de los 3 miembros.
        assertThat(colas.get(1).online()).hasSize(2);
        assertThat(colas.get(1).members()).hasSize(3);

        ColaApi classic = colas.get(2);
        assertThat(classic.type()).isEqualTo("classic");
        assertThat(classic.node()).isEqualTo("rabbit@rabbitmq3");
        assertThat(classic.leader()).isNull();
        assertThat(classic.messages()).isEqualTo(7L);

        // Recién declarada: la API todavía no manda contadores → null, no 0.
        ColaApi nueva = colas.get(3);
        assertThat(nueva.name()).isEqualTo("recien.creada");
        assertThat(nueva.messages()).isNull();
        assertThat(nueva.consumers()).isNull();
    }

    @Test
    void listarExchangesIncluyeElPorDefectoLosAmqYLosDelSistema() {
        servidor.expect(requestTo(NODO1 + "/api/exchanges/%2F"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withSuccess(json("exchanges.json"), MediaType.APPLICATION_JSON));

        List<ExchangeApi> exchanges = cliente.listarExchanges();

        assertThat(exchanges).hasSize(10);
        assertThat(exchanges.get(0).name()).isEmpty();
        assertThat(exchanges).extracting(ExchangeApi::name)
                .contains("amq.topic", "pedidos360.notificaciones", "pedidos360.notificaciones.dlx");
        ExchangeApi trace = exchanges.stream().filter(e -> e.name().equals("amq.rabbitmq.trace")).findFirst().orElseThrow();
        assertThat(trace.internal()).isTrue();
        ExchangeApi prueba = exchanges.get(9);
        assertThat(prueba.type()).isEqualTo("direct");
        assertThat(prueba.durable()).isFalse();
        assertThat(prueba.autoDelete()).isTrue();
        assertThat(prueba.arguments()).containsEntry("alternate-exchange", "amq.fanout");
    }

    @Test
    void listarBindingsParseaDestinoRoutingKeyYPropertiesKey() {
        servidor.expect(requestTo(NODO1 + "/api/bindings/%2F"))
                .andRespond(withSuccess(json("bindings.json"), MediaType.APPLICATION_JSON));

        List<BindingApi> bindings = cliente.listarBindings();

        assertThat(bindings).hasSize(6);
        BindingApi auditoria = bindings.get(2);
        assertThat(auditoria.source()).isEqualTo("pedidos360.notificaciones");
        assertThat(auditoria.destination()).isEqualTo("notificaciones.auditoria");
        assertThat(auditoria.destinationType()).isEqualTo("queue");
        assertThat(auditoria.routingKey()).isEqualTo("#");
        assertThat(auditoria.propertiesKey()).isEqualTo("%23");
        assertThat(bindings.get(5).arguments()).containsEntry("x-origen", "consola");
    }

    @Test
    void listarNodosParseaNodoVivoYNodoCaidoSinMetricas() {
        servidor.expect(requestTo(NODO1 + "/api/nodes"))
                .andRespond(withSuccess(json("nodes.json"), MediaType.APPLICATION_JSON));

        List<NodoApi> nodos = cliente.listarNodos();

        assertThat(nodos).hasSize(3);
        NodoApi vivo = nodos.get(0);
        assertThat(vivo.name()).isEqualTo("rabbit@rabbitmq1");
        assertThat(vivo.running()).isTrue();
        assertThat(vivo.memUsed()).isEqualTo(150994944L);
        assertThat(vivo.memLimit()).isEqualTo(402653184L);
        assertThat(vivo.memAlarm()).isFalse();
        assertThat(vivo.diskFreeLimit()).isEqualTo(1000000000L);
        assertThat(vivo.partitions()).isEmpty();
        assertThat(vivo.uptime()).isEqualTo(7265341L);

        NodoApi caido = nodos.get(2);
        assertThat(caido.name()).isEqualTo("rabbit@rabbitmq3");
        assertThat(caido.running()).isFalse();
        assertThat(caido.memUsed()).isNull();
        assertThat(caido.diskFree()).isNull();
    }

    @Test
    void nombreDelCluster() {
        servidor.expect(requestTo(NODO1 + "/api/cluster-name"))
                .andRespond(withSuccess(json("cluster-name.json"), MediaType.APPLICATION_JSON));

        assertThat(cliente.nombreCluster()).isEqualTo("pedidos360");
    }

    @Test
    void existeExchangeCodificaElNombreY404SignificaQueNoExiste() {
        servidor.expect(requestTo(NODO1 + "/api/exchanges/%2F/pedidos360.notificaciones"))
                .andRespond(withSuccess(json("exchange-notificaciones.json"), MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(NODO1 + "/api/exchanges/%2F/no.existe"))
                .andRespond(withResourceNotFound());

        assertThat(cliente.existeExchange("pedidos360.notificaciones")).isTrue();
        assertThat(cliente.existeExchange("no.existe")).isFalse();
        servidor.verify();
    }

    @Test
    void vhostPropioVaEnLaRuta() {
        crearCliente("pedidos", NODO1);
        servidor.expect(requestTo(NODO1 + "/api/queues/pedidos"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(cliente.listarColas()).isEmpty();
        servidor.verify();
    }

    @Test
    void nodoCaidoHaceFailoverYElSiguienteQuedaComoPreferido() {
        servidor.expect(requestTo(NODO1 + "/api/nodes")).andRespond(conexionRechazada());
        servidor.expect(requestTo(NODO2 + "/api/nodes"))
                .andRespond(withSuccess(json("nodes.json"), MediaType.APPLICATION_JSON));
        // La consulta siguiente va DIRECTO al nodo 2: no se vuelve a pagar el
        // timeout del nodo caído.
        servidor.expect(requestTo(NODO2 + "/api/cluster-name"))
                .andRespond(withSuccess(json("cluster-name.json"), MediaType.APPLICATION_JSON));

        assertThat(cliente.listarNodos()).hasSize(3);
        assertThat(cliente.urlActiva()).isEqualTo(NODO2);
        assertThat(cliente.nombreCluster()).isEqualTo("pedidos360");
        servidor.verify();
    }

    @Test
    void un5xxTambienHaceFailover() {
        servidor.expect(requestTo(NODO1 + "/api/queues/%2F")).andRespond(withServiceUnavailable());
        servidor.expect(requestTo(NODO2 + "/api/queues/%2F"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(cliente.listarColas()).isEmpty();
        servidor.verify();
    }

    @Test
    void todosLosNodosCaidosLanzaManagementNoDisponibleConCadaUrl() {
        servidor.expect(requestTo(NODO1 + "/api/nodes")).andRespond(conexionRechazada());
        servidor.expect(requestTo(NODO2 + "/api/nodes")).andRespond(conexionRechazada());

        assertThatThrownBy(() -> cliente.listarNodos())
                .isInstanceOf(ManagementNoDisponibleException.class)
                .hasMessageContaining(NODO1)
                .hasMessageContaining(NODO2);
        servidor.verify();
    }

    @Test
    void credencialesRechazadasNoHacenFailover() {
        // 401 no se arregla preguntando a otro nodo: los usuarios son del clúster.
        servidor.expect(requestTo(NODO1 + "/api/queues/%2F")).andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> cliente.listarColas())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        servidor.verify();
    }

    @Test
    void sinUrlsNoArranca() {
        RabbitManagementProperties props = new RabbitManagementProperties();
        props.setUrls(List.of(" "));
        assertThatThrownBy(() -> new ClienteManagementRabbit(RestClient.create(), props))
                .isInstanceOf(IllegalStateException.class);
    }
}
