package com.pedidos360.rabbitadmin.Service;

import com.pedidos360.rabbitadmin.Component.ClienteManagementRabbit;
import com.pedidos360.rabbitadmin.Component.ProteccionRecursos;
import com.pedidos360.rabbitadmin.Config.ProteccionRecursosProperties;
import com.pedidos360.rabbitadmin.DTO.BindingDTO;
import com.pedidos360.rabbitadmin.DTO.BindingRequestDTO;
import com.pedidos360.rabbitadmin.DTO.ColaDTO;
import com.pedidos360.rabbitadmin.DTO.CrearColaRequestDTO;
import com.pedidos360.rabbitadmin.DTO.CrearExchangeRequestDTO;
import com.pedidos360.rabbitadmin.DTO.EstadoClusterDTO;
import com.pedidos360.rabbitadmin.DTO.ExchangeDTO;
import com.pedidos360.rabbitadmin.DTO.NodoDTO;
import com.pedidos360.rabbitadmin.DTO.RespuestaOperacionDTO;
import com.pedidos360.rabbitadmin.Exception.ConflictoException;
import com.pedidos360.rabbitadmin.Exception.RecursoNoEncontradoException;
import com.pedidos360.rabbitadmin.Exception.RecursoProtegidoException;
import com.pedidos360.rabbitadmin.Exception.RecursoYaExisteException;
import com.pedidos360.rabbitadmin.Model.BindingApi;
import com.pedidos360.rabbitadmin.Model.ColaApi;
import com.pedidos360.rabbitadmin.Model.ExchangeApi;
import com.pedidos360.rabbitadmin.Model.NodoApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Reglas del servicio con AmqpAdmin y el cliente de management mockeados (sin broker). */
@ExtendWith(MockitoExtension.class)
class ServicioRabbitAdminTest {

    @Mock
    private AmqpAdmin amqpAdmin;

    @Mock
    private ClienteManagementRabbit management;

    private ServicioRabbitAdmin servicio;

    @BeforeEach
    void preparar() {
        servicio = new ServicioRabbitAdmin(amqpAdmin, management,
                new ProteccionRecursos(new ProteccionRecursosProperties()));
    }

    private static QueueInformation info(String nombre, int mensajes) {
        return new QueueInformation(nombre, mensajes, 0);
    }

    // ------------------------------------------------------------------ colas

    @Test
    void crearColaQuorumDeclaraLosArgumentosDelContrato() {
        when(amqpAdmin.getQueueInfo("pedidos.nuevos")).thenReturn(null);

        RespuestaOperacionDTO r = servicio.crearCola(new CrearColaRequestDTO(
                "pedidos.nuevos", null, null, "pedidos360.notificaciones.dlx", 5, null, null));

        ArgumentCaptor<Queue> cola = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin).declareQueue(cola.capture());
        assertThat(cola.getValue().getName()).isEqualTo("pedidos.nuevos");
        assertThat(cola.getValue().isDurable()).isTrue();
        assertThat(cola.getValue().isExclusive()).isFalse();
        assertThat(cola.getValue().isAutoDelete()).isFalse();
        assertThat(cola.getValue().getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-delivery-limit", 5)
                .containsEntry("x-dead-letter-exchange", "pedidos360.notificaciones.dlx");
        assertThat(r.mensaje()).isEqualTo("Cola creada");
        assertThat(r.detalle()).containsEntry("tipo", "quorum");
    }

    @Test
    void crearColaClassicNoDurableConTtlYLongitud() {
        servicio.crearCola(new CrearColaRequestDTO("temporal", "classic", false, null, null, 60000, 100));

        ArgumentCaptor<Queue> cola = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin).declareQueue(cola.capture());
        assertThat(cola.getValue().isDurable()).isFalse();
        assertThat(cola.getValue().getArguments())
                .containsEntry("x-queue-type", "classic")
                .containsEntry("x-message-ttl", 60000)
                .containsEntry("x-max-length", 100L)
                .doesNotContainKey("x-delivery-limit");
    }

    @Test
    void crearColaExistenteEs409YNoRedeclara() {
        when(amqpAdmin.getQueueInfo("notificaciones.cliente")).thenReturn(info("notificaciones.cliente", 0));

        assertThatThrownBy(() -> servicio.crearCola(new CrearColaRequestDTO(
                "notificaciones.cliente", "quorum", true, null, null, null, null)))
                .isInstanceOf(RecursoYaExisteException.class);
        verify(amqpAdmin, never()).declareQueue(any(Queue.class));
    }

    @Test
    void borrarColaAmqSinForzarSeRechazaSinTocarElBroker() {
        assertThatThrownBy(() -> servicio.borrarCola("amq.gen-JzTY20BRgKO", false, false))
                .isInstanceOf(RecursoProtegidoException.class)
                .hasMessageContaining("amq.");
        verifyNoInteractions(amqpAdmin);
    }

    @Test
    void borrarColaDelSistemaSinForzarSeRechaza() {
        assertThatThrownBy(() -> servicio.borrarCola("notificaciones.cliente", false, false))
                .isInstanceOf(RecursoProtegidoException.class)
                .hasMessageContaining("forzar=true");
        verifyNoInteractions(amqpAdmin);
    }

    @Test
    void borrarColaDelSistemaConForzarSeBorra() {
        when(amqpAdmin.getQueueInfo("notificaciones.cliente")).thenReturn(info("notificaciones.cliente", 4));
        when(amqpAdmin.deleteQueue("notificaciones.cliente")).thenReturn(true);

        RespuestaOperacionDTO r = servicio.borrarCola("notificaciones.cliente", false, true);

        verify(amqpAdmin).deleteQueue("notificaciones.cliente");
        assertThat(r.detalle()).containsEntry("forzado", true).containsEntry("mensajesDescartados", 4);
    }

    @Test
    void borrarColaInexistenteEs404() {
        when(amqpAdmin.getQueueInfo("no.existe")).thenReturn(null);

        assertThatThrownBy(() -> servicio.borrarCola("no.existe", false, false))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(amqpAdmin, never()).deleteQueue(anyString());
    }

    @Test
    void borrarColaSiVaciaConMensajesEs409YNoSeBorra() {
        when(amqpAdmin.getQueueInfo("prueba.classic")).thenReturn(info("prueba.classic", 7));

        assertThatThrownBy(() -> servicio.borrarCola("prueba.classic", true, false))
                .isInstanceOf(ConflictoException.class)
                .hasMessageContaining("7 mensajes");
        verify(amqpAdmin, never()).deleteQueue(anyString());
        verify(amqpAdmin, never()).deleteQueue(anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void borrarColaSiVaciaVaciaPideIfEmptyAlBroker() {
        when(amqpAdmin.getQueueInfo("prueba.classic")).thenReturn(info("prueba.classic", 0));

        servicio.borrarCola("prueba.classic", true, false);

        verify(amqpAdmin).deleteQueue("prueba.classic", false, true);
    }

    @Test
    void borrarColaQueElBrokerRechazaEs409() {
        when(amqpAdmin.getQueueInfo("exclusiva")).thenReturn(info("exclusiva", 0));
        when(amqpAdmin.deleteQueue("exclusiva")).thenReturn(false);

        assertThatThrownBy(() -> servicio.borrarCola("exclusiva", false, false))
                .isInstanceOf(ConflictoException.class);
    }

    @Test
    void purgarDevuelveLosMensajesEliminadosY404SiNoExiste() {
        when(amqpAdmin.getQueueInfo("notificaciones.cliente.dlq")).thenReturn(info("notificaciones.cliente.dlq", 2));
        when(amqpAdmin.purgeQueue("notificaciones.cliente.dlq")).thenReturn(2);
        when(amqpAdmin.getQueueInfo("no.existe")).thenReturn(null);

        assertThat(servicio.purgarCola("notificaciones.cliente.dlq").detalle()).containsEntry("mensajesEliminados", 2);
        assertThatThrownBy(() -> servicio.purgarCola("no.existe")).isInstanceOf(RecursoNoEncontradoException.class);
        verify(amqpAdmin, never()).purgeQueue("no.existe");
    }

    @Test
    void listarColasMapeaMarcaProtegidasFiltraYOrdena() {
        ColaApi quorum = new ColaApi("notificaciones.cliente", "/", "quorum", true, false, false,
                Map.of("x-queue-type", "quorum"), 3L, 2L, 1L, 1, "running", "rabbit@rabbitmq2", "rabbit@rabbitmq2",
                List.of("rabbit@rabbitmq1", "rabbit@rabbitmq2", "rabbit@rabbitmq3"),
                List.of("rabbit@rabbitmq1", "rabbit@rabbitmq2"), null);
        ColaApi vacia = new ColaApi("a.vacia", "/", "classic", true, false, false,
                null, 0L, 0L, 0L, 0, "running", "rabbit@rabbitmq3", null, null, null, null);
        ColaApi sinStats = new ColaApi("b.nueva", "/", "classic", true, false, false,
                Map.of(), null, null, null, null, "running", "rabbit@rabbitmq1", null, null, null, null);
        when(management.listarColas()).thenReturn(List.of(quorum, vacia, sinStats));

        List<ColaDTO> todas = servicio.listarColas(false);
        assertThat(todas).extracting(ColaDTO::nombre).containsExactly("a.vacia", "b.nueva", "notificaciones.cliente");
        ColaDTO cliente = todas.get(2);
        assertThat(cliente.protegida()).isTrue();
        assertThat(cliente.nodo()).isEqualTo("rabbit@rabbitmq2");
        assertThat(cliente.miembrosEnLinea()).hasSize(2);
        assertThat(todas.get(0).protegida()).isFalse();
        assertThat(todas.get(0).nodo()).isEqualTo("rabbit@rabbitmq3");
        assertThat(todas.get(0).argumentos()).isEmpty();

        assertThat(servicio.listarColas(true)).extracting(ColaDTO::nombre).containsExactly("notificaciones.cliente");
    }

    // -------------------------------------------------------------- exchanges

    @Test
    void crearExchangeTopicDurable() {
        when(management.existeExchange("pedidos.eventos")).thenReturn(false);

        servicio.crearExchange(new CrearExchangeRequestDTO("pedidos.eventos", "topic", null, null, null, "amq.fanout"));

        ArgumentCaptor<Exchange> ex = ArgumentCaptor.forClass(Exchange.class);
        verify(amqpAdmin).declareExchange(ex.capture());
        assertThat(ex.getValue().getName()).isEqualTo("pedidos.eventos");
        assertThat(ex.getValue().getType()).isEqualTo(ExchangeTypes.TOPIC);
        assertThat(ex.getValue().isDurable()).isTrue();
        assertThat(ex.getValue().isAutoDelete()).isFalse();
        assertThat(ex.getValue().getArguments()).containsEntry("alternate-exchange", "amq.fanout");
    }

    @Test
    void crearExchangeExistenteEs409() {
        when(management.existeExchange("pedidos360.notificaciones")).thenReturn(true);

        assertThatThrownBy(() -> servicio.crearExchange(new CrearExchangeRequestDTO(
                "pedidos360.notificaciones", "topic", true, false, false, null)))
                .isInstanceOf(RecursoYaExisteException.class);
        verify(amqpAdmin, never()).declareExchange(any());
    }

    @Test
    void borrarExchangeAmqSinForzarSeRechazaSinConsultarNada() {
        assertThatThrownBy(() -> servicio.borrarExchange("amq.topic", false))
                .isInstanceOf(RecursoProtegidoException.class);
        verifyNoInteractions(amqpAdmin, management);
    }

    @Test
    void borrarExchangeAmqForzadoQueElBrokerRechazaSigueSiendo403() {
        when(management.existeExchange("amq.direct")).thenReturn(true);
        when(amqpAdmin.deleteExchange("amq.direct")).thenReturn(false);

        assertThatThrownBy(() -> servicio.borrarExchange("amq.direct", true))
                .isInstanceOf(RecursoProtegidoException.class)
                .hasMessageContaining("ni forzando");
    }

    @Test
    void borrarExchangeInexistenteEs404YComunSeBorra() {
        when(management.existeExchange("no.existe")).thenReturn(false);
        when(management.existeExchange("prueba.directo")).thenReturn(true);
        when(amqpAdmin.deleteExchange("prueba.directo")).thenReturn(true);

        assertThatThrownBy(() -> servicio.borrarExchange("no.existe", false))
                .isInstanceOf(RecursoNoEncontradoException.class);
        assertThat(servicio.borrarExchange("prueba.directo", false).mensaje()).isEqualTo("Exchange borrado");
        verify(amqpAdmin, never()).deleteExchange("no.existe");
    }

    @Test
    void listarExchangesMarcaPorDefectoAmqYSistemaComoProtegidos() {
        when(management.listarExchanges()).thenReturn(List.of(
                new ExchangeApi("prueba.directo", "/", "direct", false, true, false, null),
                new ExchangeApi("", "/", "direct", true, false, false, Map.of()),
                new ExchangeApi("amq.topic", "/", "topic", true, false, false, Map.of()),
                new ExchangeApi("pedidos360.notificaciones", "/", "topic", true, false, false, Map.of())));

        List<ExchangeDTO> lista = servicio.listarExchanges();

        assertThat(lista).extracting(ExchangeDTO::nombre)
                .containsExactly("", "amq.topic", "pedidos360.notificaciones", "prueba.directo");
        assertThat(lista).extracting(ExchangeDTO::protegido).containsExactly(true, true, true, false);
    }

    // --------------------------------------------------------------- bindings

    @Test
    void crearBindingAColaDeclaraOrigenDestinoYRoutingKey() {
        when(management.existeExchange("pedidos360.notificaciones")).thenReturn(true);
        when(amqpAdmin.getQueueInfo("prueba.classic")).thenReturn(info("prueba.classic", 0));

        servicio.crearBinding(new BindingRequestDTO("pedidos360.notificaciones", "prueba.classic", null, "pedido.*", null));

        ArgumentCaptor<Binding> b = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(b.capture());
        assertThat(b.getValue().getExchange()).isEqualTo("pedidos360.notificaciones");
        assertThat(b.getValue().getDestination()).isEqualTo("prueba.classic");
        assertThat(b.getValue().getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        assertThat(b.getValue().getRoutingKey()).isEqualTo("pedido.*");
    }

    @Test
    void crearBindingExchangeAExchange() {
        when(management.existeExchange("amq.topic")).thenReturn(true);
        when(management.existeExchange("prueba.directo")).thenReturn(true);

        servicio.crearBinding(new BindingRequestDTO("amq.topic", "prueba.directo", "exchange", "#", null));

        ArgumentCaptor<Binding> b = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(b.capture());
        assertThat(b.getValue().getDestinationType()).isEqualTo(Binding.DestinationType.EXCHANGE);
    }

    @Test
    void crearBindingConPuntaInexistenteEs404YDiceCual() {
        when(management.existeExchange("no.existe")).thenReturn(false);
        assertThatThrownBy(() -> servicio.crearBinding(new BindingRequestDTO("no.existe", "q", null, "", null)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessageContaining("exchange origen");

        when(management.existeExchange("amq.fanout")).thenReturn(true);
        when(amqpAdmin.getQueueInfo("cola.fantasma")).thenReturn(null);
        assertThatThrownBy(() -> servicio.crearBinding(new BindingRequestDTO("amq.fanout", "cola.fantasma", null, "", null)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessageContaining("cola destino");
        verify(amqpAdmin, never()).declareBinding(any());
    }

    @Test
    void borrarBindingDelSistemaExigeForzar() {
        BindingRequestDTO req = new BindingRequestDTO("pedidos360.notificaciones", "notificaciones.cliente", null, "pedido.*", null);

        assertThatThrownBy(() -> servicio.borrarBinding(req, false)).isInstanceOf(RecursoProtegidoException.class);
        verifyNoInteractions(amqpAdmin);

        servicio.borrarBinding(req, true);
        ArgumentCaptor<Binding> b = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).removeBinding(b.capture());
        assertThat(b.getValue().getRoutingKey()).isEqualTo("pedido.*");
    }

    @Test
    void listarBindingsOcultaLosImplicitosDelExchangePorDefecto() {
        when(management.listarBindings()).thenReturn(List.of(
                new BindingApi("", "/", "prueba.classic", "queue", "prueba.classic", Map.of(), "prueba.classic"),
                new BindingApi("pedidos360.notificaciones", "/", "notificaciones.cliente", "queue", "pedido.*", Map.of(), "pedido.*"),
                new BindingApi("amq.topic", "/", "prueba.classic", "queue", "prueba.#", null, "prueba.%23")));

        List<BindingDTO> sinImplicitos = servicio.listarBindings(false);
        assertThat(sinImplicitos).extracting(BindingDTO::origen).containsExactly("amq.topic", "pedidos360.notificaciones");
        assertThat(sinImplicitos).extracting(BindingDTO::protegido).containsExactly(false, true);

        List<BindingDTO> todos = servicio.listarBindings(true);
        assertThat(todos).hasSize(3);
        assertThat(todos.get(0).origen()).isEmpty();
        assertThat(todos.get(0).protegido()).isTrue();
    }

    // ---------------------------------------------------------------- cluster

    private static NodoApi vivo(String nombre, long mem) {
        return new NodoApi(nombre, "disc", true, mem, 402653184L, false, 53687091200L, 1000000000L, false, List.of(), 7265341L);
    }

    private static NodoApi caido(String nombre) {
        return new NodoApi(nombre, "disc", false, null, null, null, null, null, null, null, null);
    }

    @Test
    void clusterConUnNodoCaidoSigueTeniendoQuorum() {
        when(management.listarNodos()).thenReturn(List.of(
                caido("rabbit@rabbitmq3"), vivo("rabbit@rabbitmq1", 150994944L), vivo("rabbit@rabbitmq2", 120795955L)));
        when(management.nombreCluster()).thenReturn("pedidos360");
        when(management.urlActiva()).thenReturn("http://rabbitmq2:15672");

        EstadoClusterDTO c = servicio.estadoCluster();

        assertThat(c.nombreCluster()).isEqualTo("pedidos360");
        assertThat(c.totalNodos()).isEqualTo(3);
        assertThat(c.nodosCorriendo()).isEqualTo(2);
        assertThat(c.hayQuorum()).isTrue();
        assertThat(c.hayParticiones()).isFalse();
        assertThat(c.hayAlarmas()).isFalse();
        assertThat(c.fuente()).isEqualTo("http://rabbitmq2:15672");
        assertThat(c.nodos()).extracting(NodoDTO::nombre)
                .containsExactly("rabbit@rabbitmq1", "rabbit@rabbitmq2", "rabbit@rabbitmq3");
        NodoDTO n1 = c.nodos().get(0);
        // 150994944 / 402653184 = 37.5 % del high watermark.
        assertThat(n1.porcentajeMemoria()).isEqualTo(37.5);
        NodoDTO n3 = c.nodos().get(2);
        assertThat(n3.corriendo()).isFalse();
        assertThat(n3.porcentajeMemoria()).isNull();
        assertThat(n3.particiones()).isEmpty();
    }

    @Test
    void clusterConDosNodosCaidosPierdeElQuorumYDetectaParticionesYAlarmas() {
        NodoApi particionado = new NodoApi("rabbit@rabbitmq1", "disc", true, 400000000L, 402653184L, true,
                1L, 1000000000L, false, List.of("rabbit@rabbitmq2"), 10L);
        when(management.listarNodos()).thenReturn(List.of(particionado, caido("rabbit@rabbitmq2"), caido("rabbit@rabbitmq3")));

        EstadoClusterDTO c = servicio.estadoCluster();

        assertThat(c.nodosCorriendo()).isEqualTo(1);
        assertThat(c.hayQuorum()).isFalse();
        assertThat(c.hayParticiones()).isTrue();
        assertThat(c.hayAlarmas()).isTrue();
    }
}
