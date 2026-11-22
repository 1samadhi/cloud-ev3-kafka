package com.kafka.admin.Service;

import com.kafka.admin.Config.KafkaAdminProperties;
import com.kafka.admin.DTO.*;
import com.kafka.admin.Exception.ConflictoException;
import com.kafka.admin.Exception.KafkaNoDisponibleException;
import com.kafka.admin.Exception.RecursoNoEncontradoException;
import com.kafka.admin.Exception.SolicitudInvalidaException;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reglas del servicio con el AdminClient mockeado: lo que importa es qué se
 * le pide a Kafka (y sobre todo qué NO se le pide cuando la regla lo prohíbe).
 */
class KafkaAdminServiceTest {

    private static final Node BROKER_1 = new Node(1, "kafka1", 29092);
    private static final Node BROKER_2 = new Node(2, "kafka2", 29092);
    private static final Node BROKER_3 = new Node(3, "kafka3", 29092);

    private AdminClient admin;
    private KafkaAdminService servicio;

    @BeforeEach
    void preparar() {
        admin = mock(AdminClient.class);
        KafkaAdminProperties props = new KafkaAdminProperties();
        props.setTimeoutMs(500);
        servicio = new KafkaAdminService(admin, new ValidadorConfiguracionTopico(), props);
    }

    // ------------------------------------------------------------ particiones

    @Nested
    class Particiones {

        @Test
        void igualALasActualesSeRechazaSinLlamarACreatePartitions() {
            topicoExistente("payment-attempts", 3, 3);

            assertThatThrownBy(() -> servicio.aumentarParticiones("payment-attempts", 3))
                    .isInstanceOf(SolicitudInvalidaException.class)
                    .hasMessageContaining("ya tiene 3 particiones")
                    .hasMessageContaining("no permite reducir");
            verify(admin, never()).createPartitions(anyMap());
        }

        @Test
        void menorALasActualesSeRechazaSinLlamarACreatePartitions() {
            topicoExistente("interacciones-user", 6, 3);

            assertThatThrownBy(() -> servicio.aumentarParticiones("interacciones-user", 2))
                    .isInstanceOf(SolicitudInvalidaException.class);
            verify(admin, never()).createPartitions(anyMap());
        }

        @Test
        void mayorLlamaAIncreaseToYAdvierteSiElDltQuedaCorto() {
            topicoExistente("interacciones-user", 3, 3);
            topicoExistente("interacciones-user.DLT", 3, 3);
            listaDeTopicos("interacciones-user", "interacciones-user.DLT");
            CreatePartitionsResult r = mock(CreatePartitionsResult.class);
            when(r.all()).thenReturn(KafkaFuture.completedFuture(null));
            when(admin.createPartitions(anyMap())).thenReturn(r);

            ResultadoParticionesDTO res = servicio.aumentarParticiones("interacciones-user", 6);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, NewPartitions>> captor = ArgumentCaptor.forClass(Map.class);
            verify(admin).createPartitions(captor.capture());
            assertThat(captor.getValue().get("interacciones-user").totalCount()).isEqualTo(6);
            assertThat(res.getParticionesAnteriores()).isEqualTo(3);
            assertThat(res.getParticionesActuales()).isEqualTo(6);
            assertThat(res.getAdvertencias()).anyMatch(a -> a.contains("interacciones-user.DLT") && a.contains("MISMA"));
        }

        @Test
        void topicoInexistenteEs404() {
            topicoInexistente("no-existe");
            assertThatThrownBy(() -> servicio.aumentarParticiones("no-existe", 5))
                    .isInstanceOf(RecursoNoEncontradoException.class)
                    .hasMessage("El tópico 'no-existe' no existe");
        }

        @Test
        void topicoInternoSeRechaza() {
            assertThatThrownBy(() -> servicio.aumentarParticiones("__consumer_offsets", 100))
                    .isInstanceOf(SolicitudInvalidaException.class);
            verifyNoInteractions(admin);
        }
    }

    // ---------------------------------------------------------- configuración

    @Nested
    class Configuracion {

        @Test
        void claveFueraDeListaBlancaSeRechazaSinAlterar() {
            topicoExistente("pagos-resultado", 3, 3);
            ModificarConfiguracionRequestDTO req = new ModificarConfiguracionRequestDTO(
                    Map.of("unclean.leader.election.enable", "true"), null);

            assertThatThrownBy(() -> servicio.modificarConfiguracionTopico("pagos-resultado", req))
                    .isInstanceOf(SolicitudInvalidaException.class)
                    .hasMessageContaining("lista blanca");
            verify(admin, never()).incrementalAlterConfigs(anyMap());
        }

        @Test
        void minInsyncMayorQueElRfDelTopicoSeRechaza() {
            topicoExistente("pagos-resultado", 3, 2);
            ModificarConfiguracionRequestDTO req = new ModificarConfiguracionRequestDTO(
                    Map.of("min.insync.replicas", "3"), null);

            assertThatThrownBy(() -> servicio.modificarConfiguracionTopico("pagos-resultado", req))
                    .isInstanceOf(SolicitudInvalidaException.class)
                    .hasMessageContaining("(2)");
            verify(admin, never()).incrementalAlterConfigs(anyMap());
        }

        @Test
        void peticionVaciaOClaveEnAmbasListasSeRechazan() {
            assertThatThrownBy(() -> servicio.modificarConfiguracionTopico("t",
                    new ModificarConfiguracionRequestDTO(Map.of(), List.of())))
                    .isInstanceOf(SolicitudInvalidaException.class);
            assertThatThrownBy(() -> servicio.modificarConfiguracionTopico("t",
                    new ModificarConfiguracionRequestDTO(Map.of("retention.ms", "1000"), List.of("retention.ms"))))
                    .isInstanceOf(SolicitudInvalidaException.class)
                    .hasMessageContaining("a la vez");
            verifyNoInteractions(admin);
        }

        @Test
        void peticionValidaGeneraSetYDeleteYDevuelveLaConfigActualizada() {
            topicoExistente("pagos-resultado", 3, 3);
            AlterConfigsResult r = mock(AlterConfigsResult.class);
            when(r.all()).thenReturn(KafkaFuture.completedFuture(null));
            when(admin.incrementalAlterConfigs(anyMap())).thenReturn(r);
            configDeTopico("pagos-resultado",
                    entrada("retention.ms", "86400000", ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG),
                    entrada("cleanup.policy", "compact,delete", ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG),
                    entrada("segment.ms", "604800000", ConfigEntry.ConfigSource.DEFAULT_CONFIG));

            Map<String, String> establecer = new LinkedHashMap<>();
            establecer.put("retention.ms", "86400000");
            establecer.put("cleanup.policy", "compact, delete");
            ConfiguracionRecursoDTO res = servicio.modificarConfiguracionTopico("pagos-resultado",
                    new ModificarConfiguracionRequestDTO(establecer, List.of("segment.ms")));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<ConfigResource, Collection<AlterConfigOp>>> captor = ArgumentCaptor.forClass(Map.class);
            verify(admin).incrementalAlterConfigs(captor.capture());
            Collection<AlterConfigOp> ops = captor.getValue()
                    .get(new ConfigResource(ConfigResource.Type.TOPIC, "pagos-resultado"));
            assertThat(ops).extracting(o -> o.opType() + " " + o.configEntry().name() + "=" + o.configEntry().value())
                    .containsExactly(
                            "SET retention.ms=86400000",
                            "SET cleanup.policy=compact,delete",
                            "DELETE segment.ms=null");
            assertThat(res.getSobrescritas()).containsEntry("retention.ms", "86400000").doesNotContainKey("segment.ms");
        }

        @Test
        void describirMarcaSobrescritasYPorDefecto() {
            configDeTopico("catalogo-productos",
                    entrada("cleanup.policy", "compact", ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG),
                    entrada("min.insync.replicas", "2", ConfigEntry.ConfigSource.STATIC_BROKER_CONFIG),
                    entrada("flush.ms", "9223372036854775807", ConfigEntry.ConfigSource.DEFAULT_CONFIG));

            ConfiguracionRecursoDTO res = servicio.obtenerConfiguracionTopico("catalogo-productos");

            assertThat(res.getTipo()).isEqualTo("TOPIC");
            assertThat(res.getSobrescritas()).containsOnlyKeys("cleanup.policy");
            EntradaConfiguracionDTO cleanup = res.getEntradas().stream()
                    .filter(e -> e.getNombre().equals("cleanup.policy")).findFirst().orElseThrow();
            assertThat(cleanup.isPorDefecto()).isFalse();
            assertThat(cleanup.isEditable()).isTrue();
            EntradaConfiguracionDTO heredada = res.getEntradas().stream()
                    .filter(e -> e.getNombre().equals("min.insync.replicas")).findFirst().orElseThrow();
            assertThat(heredada.isPorDefecto()).as("heredada del broker = por defecto para el tópico").isTrue();
            EntradaConfiguracionDTO noEditable = res.getEntradas().stream()
                    .filter(e -> e.getNombre().equals("flush.ms")).findFirst().orElseThrow();
            assertThat(noEditable.isEditable()).isFalse();
        }

        @Test
        void describirTopicoInexistenteEs404() {
            ConfigResource recurso = new ConfigResource(ConfigResource.Type.TOPIC, "fantasma");
            DescribeConfigsResult r = mock(DescribeConfigsResult.class);
            when(r.all()).thenReturn(fallido(new UnknownTopicOrPartitionException("no está")));
            when(admin.describeConfigs(List.of(recurso))).thenReturn(r);

            assertThatThrownBy(() -> servicio.obtenerConfiguracionTopico("fantasma"))
                    .isInstanceOf(RecursoNoEncontradoException.class)
                    .hasMessage("El tópico 'fantasma' no existe");
        }

        @Test
        void brokerInexistenteEs404() {
            cluster(BROKER_1, BROKER_2, BROKER_3);
            assertThatThrownBy(() -> servicio.obtenerConfiguracionBroker(7))
                    .isInstanceOf(RecursoNoEncontradoException.class)
                    .hasMessageContaining("[1, 2, 3]");
            verify(admin, never()).describeConfigs(anyCollection());
        }
    }

    // ------------------------------------------------------- consumer groups

    @Nested
    class ConsumerGroups {

        @Test
        void resetConMiembrosActivosEs409YNoTocaOffsets() {
            grupo("grupo-antifraude", ConsumerGroupState.STABLE,
                    miembro("m-1", "ms-pago-consumer-0", new TopicPartition("payment-attempts", 0)));

            assertThatThrownBy(() -> servicio.resetearOffsets("grupo-antifraude",
                    new ResetOffsetsRequestDTO("payment-attempts", EstrategiaReset.EARLIEST, null, null)))
                    .isInstanceOf(ConflictoException.class)
                    .hasMessageContaining("1 miembro(s) activo(s)")
                    .hasMessageContaining("ms-pago-consumer-0");
            verify(admin, never()).alterConsumerGroupOffsets(anyString(), anyMap());
        }

        @Test
        void resetEarliestMueveTodasLasParticionesAlInicio() {
            grupo("grupo-dashboard", ConsumerGroupState.EMPTY);
            topicoExistente("interacciones-user", 2, 3);
            offsets(Map.of(0, 5L, 1, 3L), Map.of(0, 100L, 1, 80L), Map.of());
            confirmados("grupo-dashboard", Map.of(new TopicPartition("interacciones-user", 0), new OffsetAndMetadata(90)));
            AlterConsumerGroupOffsetsResult r = alterOk("grupo-dashboard");

            ResultadoResetOffsetsDTO res = servicio.resetearOffsets("grupo-dashboard",
                    new ResetOffsetsRequestDTO("interacciones-user", EstrategiaReset.EARLIEST, null, null));

            Map<TopicPartition, OffsetAndMetadata> enviados = capturarAlter("grupo-dashboard");
            assertThat(enviados.get(new TopicPartition("interacciones-user", 0)).offset()).isEqualTo(5);
            assertThat(enviados.get(new TopicPartition("interacciones-user", 1)).offset()).isEqualTo(3);
            assertThat(res.getParticiones()).extracting(OffsetReseteadoDTO::getOffsetAnterior).containsExactly(90L, null);
            assertThat(r).isNotNull();
        }

        @Test
        void resetOffsetFueraDeRangoSeAjustaAlFinalYSoloEnLaParticionPedida() {
            grupo("g", ConsumerGroupState.EMPTY);
            topicoExistente("t", 3, 1);
            offsets(Map.of(1, 0L), Map.of(1, 50L), Map.of());
            confirmados("g", Map.of());
            alterOk("g");

            ResultadoResetOffsetsDTO res = servicio.resetearOffsets("g",
                    new ResetOffsetsRequestDTO("t", EstrategiaReset.OFFSET, 999L, List.of(1)));

            assertThat(capturarAlter("g")).containsOnlyKeys(new TopicPartition("t", 1));
            assertThat(res.getParticiones()).singleElement().satisfies(p -> {
                assertThat(p.getOffsetNuevo()).isEqualTo(50);
                assertThat(p.isAjustado()).isTrue();
            });
        }

        @Test
        void resetPorTimestampSinMensajesPosterioresVaAlFinal() {
            grupo("g", ConsumerGroupState.EMPTY);
            topicoExistente("t", 1, 1);
            offsets(Map.of(0, 0L), Map.of(0, 40L), Map.of(0, -1L));
            confirmados("g", Map.of());
            alterOk("g");

            ResultadoResetOffsetsDTO res = servicio.resetearOffsets("g",
                    new ResetOffsetsRequestDTO("t", EstrategiaReset.TIMESTAMP, 4_102_444_800_000L, null));

            assertThat(res.getParticiones().get(0).getOffsetNuevo()).isEqualTo(40);
        }

        @Test
        void resetSobreParticionInexistenteEs400() {
            grupo("g", ConsumerGroupState.EMPTY);
            topicoExistente("t", 3, 1);
            assertThatThrownBy(() -> servicio.resetearOffsets("g",
                    new ResetOffsetsRequestDTO("t", EstrategiaReset.LATEST, null, List.of(3))))
                    .isInstanceOf(SolicitudInvalidaException.class)
                    .hasMessageContaining("0..2");
        }

        @Test
        void resetSinValorConTimestampEs400SinConsultarKafka() {
            assertThatThrownBy(() -> servicio.resetearOffsets("g",
                    new ResetOffsetsRequestDTO("t", EstrategiaReset.TIMESTAMP, null, null)))
                    .isInstanceOf(SolicitudInvalidaException.class);
            verifyNoInteractions(admin);
        }

        @Test
        void grupoDeadEsInexistente404() {
            grupo("fantasma", ConsumerGroupState.DEAD);
            assertThatThrownBy(() -> servicio.obtenerConsumerGroupDetalle("fantasma"))
                    .isInstanceOf(RecursoNoEncontradoException.class);
            assertThatThrownBy(() -> servicio.eliminarConsumerGroup("fantasma"))
                    .isInstanceOf(RecursoNoEncontradoException.class);
        }

        @Test
        void eliminarConMiembrosEs409YVacioLlamaADelete() {
            grupo("ocupado", ConsumerGroupState.STABLE, miembro("m", "c", new TopicPartition("t", 0)));
            assertThatThrownBy(() -> servicio.eliminarConsumerGroup("ocupado")).isInstanceOf(ConflictoException.class);
            verify(admin, never()).deleteConsumerGroups(anyCollection());

            grupo("vacio", ConsumerGroupState.EMPTY);
            DeleteConsumerGroupsResult r = mock(DeleteConsumerGroupsResult.class);
            when(r.all()).thenReturn(KafkaFuture.completedFuture(null));
            when(admin.deleteConsumerGroups(List.of("vacio"))).thenReturn(r);
            servicio.eliminarConsumerGroup("vacio");
            verify(admin).deleteConsumerGroups(List.of("vacio"));
        }

        @Test
        void detalleIncluyeCoordinadorEstrategiaYAsignaciones() {
            grupo("grupo-recomendaciones", ConsumerGroupState.STABLE,
                    miembro("m-b", "rec-1", new TopicPartition("interacciones-user", 3), new TopicPartition("interacciones-user", 2)),
                    miembro("m-a", "rec-0", new TopicPartition("interacciones-user", 0), new TopicPartition("interacciones-user", 1)));

            ConsumerGroupDetalleDTO d = servicio.obtenerConsumerGroupDetalle("grupo-recomendaciones");

            assertThat(d.getEstado()).isEqualTo("Stable");
            assertThat(d.getCoordinador().getId()).isEqualTo(2);
            assertThat(d.getEstrategia()).isEqualTo("cooperative-sticky");
            assertThat(d.getMiembros()).extracting(MiembroConsumerGroupDTO::getIdMiembro).containsExactly("m-a", "m-b");
            assertThat(d.getMiembros().get(1).getAsignaciones()).extracting(AsignacionParticionDTO::getParticion)
                    .containsExactly(2, 3);
        }
    }

    // ------------------------------------------------------ creación y otros

    @Test
    void crearConRfMayorQueBrokersVivosEs400() {
        cluster(BROKER_1, BROKER_2);
        assertThatThrownBy(() -> servicio.crearTopico(new CrearTopicoRequestDTO("nuevo", 3, 3, null, null, null)))
                .isInstanceOf(SolicitudInvalidaException.class)
                .hasMessageContaining("brokers vivos (2)");
        verify(admin, never()).createTopics(anyCollection());
    }

    @Test
    void crearConConfigFueraDeListaBlancaEs400() {
        cluster(BROKER_1, BROKER_2, BROKER_3);
        CrearTopicoRequestDTO req = new CrearTopicoRequestDTO("nuevo", 3, 3, null, null, null,
                Map.of("message.timestamp.type", "LogAppendTime"));
        assertThatThrownBy(() -> servicio.crearTopico(req)).isInstanceOf(SolicitudInvalidaException.class);
        verify(admin, never()).createTopics(anyCollection());
    }

    @Test
    void crearConRf1UsaMinInsync1PorDefectoYAplicaConfigs() {
        cluster(BROKER_1);
        listaDeTopicos();
        CreateTopicsResult r = mock(CreateTopicsResult.class);
        when(r.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(admin.createTopics(anyCollection())).thenReturn(r);

        servicio.crearTopico(new CrearTopicoRequestDTO("nuevo", 2, 1, null, null, null, Map.of("compression.type", "lz4")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<NewTopic>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(admin).createTopics(captor.capture());
        NewTopic creado = captor.getValue().iterator().next();
        assertThat(creado.configs()).containsEntry("min.insync.replicas", "1")
                .containsEntry("compression.type", "lz4")
                .containsEntry("retention.ms", "604800000");
    }

    @Test
    void topicoExistenteAlCrearEs409() {
        cluster(BROKER_1, BROKER_2, BROKER_3);
        listaDeTopicos("payment-attempts");
        assertThatThrownBy(() -> servicio.crearTopico(new CrearTopicoRequestDTO("payment-attempts", 3, 3, null, null, null)))
                .isInstanceOf(ConflictoException.class)
                .hasMessage("El tópico 'payment-attempts' ya existe");
    }

    @Test
    void losTopicosDelProyectoYSusDltEstanProtegidos() {
        assertThatThrownBy(() -> servicio.eliminarTopico("catalogo-productos.DLT"))
                .isInstanceOf(SolicitudInvalidaException.class);
        verifyNoInteractions(admin);
    }

    @Test
    void clusterQueNoRespondeEs503() {
        DescribeClusterResult r = mock(DescribeClusterResult.class);
        when(r.nodes()).thenReturn(new KafkaFutureImpl<>()); // nunca se completa
        when(admin.describeCluster()).thenReturn(r);

        assertThatThrownBy(() -> servicio.obtenerConfiguracionBroker(1))
                .isInstanceOf(KafkaNoDisponibleException.class)
                .hasMessageContaining("500 ms");
    }

    // ============================================================ auxiliares

    private static <T> KafkaFuture<T> fallido(Throwable t) {
        KafkaFutureImpl<T> f = new KafkaFutureImpl<>();
        f.completeExceptionally(t);
        return f;
    }

    private void topicoExistente(String nombre, int particiones, int rf) {
        List<Node> replicas = List.of(BROKER_1, BROKER_2, BROKER_3).subList(0, rf);
        List<TopicPartitionInfo> infos = new ArrayList<>();
        for (int i = 0; i < particiones; i++) {
            infos.add(new TopicPartitionInfo(i, replicas.get(0), replicas, replicas));
        }
        DescribeTopicsResult r = mock(DescribeTopicsResult.class);
        when(r.allTopicNames()).thenReturn(KafkaFuture.completedFuture(
                Map.of(nombre, new TopicDescription(nombre, false, infos))));
        when(admin.describeTopics(List.of(nombre))).thenReturn(r);
    }

    private void topicoInexistente(String nombre) {
        DescribeTopicsResult r = mock(DescribeTopicsResult.class);
        when(r.allTopicNames()).thenReturn(fallido(new UnknownTopicOrPartitionException("no está")));
        when(admin.describeTopics(List.of(nombre))).thenReturn(r);
    }

    private void listaDeTopicos(String... nombres) {
        ListTopicsResult r = mock(ListTopicsResult.class);
        when(r.names()).thenReturn(KafkaFuture.completedFuture(new HashSet<>(Arrays.asList(nombres))));
        when(admin.listTopics()).thenReturn(r);
    }

    private static ConfigEntry entrada(String nombre, String valor, ConfigEntry.ConfigSource origen) {
        return new ConfigEntry(nombre, valor, origen, false, false, List.of(), ConfigEntry.ConfigType.STRING, null);
    }

    private void configDeTopico(String nombre, ConfigEntry... entradas) {
        ConfigResource recurso = new ConfigResource(ConfigResource.Type.TOPIC, nombre);
        DescribeConfigsResult r = mock(DescribeConfigsResult.class);
        when(r.all()).thenReturn(KafkaFuture.completedFuture(Map.of(recurso, new Config(List.of(entradas)))));
        when(admin.describeConfigs(List.of(recurso))).thenReturn(r);
    }

    private void cluster(Node... nodos) {
        DescribeClusterResult r = mock(DescribeClusterResult.class);
        when(r.nodes()).thenReturn(KafkaFuture.completedFuture(List.of(nodos)));
        when(r.controller()).thenReturn(KafkaFuture.completedFuture(nodos[0]));
        when(admin.describeCluster()).thenReturn(r);
    }

    private static MemberDescription miembro(String id, String cliente, TopicPartition... asignadas) {
        return new MemberDescription(id, Optional.empty(), cliente, "/10.0.0.1",
                new MemberAssignment(new HashSet<>(Arrays.asList(asignadas))));
    }

    private void grupo(String id, ConsumerGroupState estado, MemberDescription... miembros) {
        ConsumerGroupDescription d = new ConsumerGroupDescription(id, false, List.of(miembros),
                "cooperative-sticky", estado, BROKER_2);
        when(admin.describeConsumerGroups(List.of(id)))
                .thenReturn(new DescribeConsumerGroupsResult(Map.of(id, KafkaFuture.completedFuture(d))));
    }

    /** Responde listOffsets según el tipo de OffsetSpec pedido (partición -> offset). */
    private void offsets(Map<Integer, Long> inicio, Map<Integer, Long> fin, Map<Integer, Long> porTimestamp) {
        when(admin.listOffsets(anyMap())).thenAnswer(inv -> {
            Map<TopicPartition, OffsetSpec> pedido = inv.getArgument(0);
            Map<TopicPartition, KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo>> r = new HashMap<>();
            pedido.forEach((tp, spec) -> {
                Map<Integer, Long> fuente = spec instanceof OffsetSpec.EarliestSpec ? inicio
                        : spec instanceof OffsetSpec.LatestSpec ? fin : porTimestamp;
                r.put(tp, KafkaFuture.completedFuture(
                        new ListOffsetsResult.ListOffsetsResultInfo(fuente.get(tp.partition()), -1, Optional.empty())));
            });
            return new ListOffsetsResult(r);
        });
    }

    private void confirmados(String grupo, Map<TopicPartition, OffsetAndMetadata> offsets) {
        ListConsumerGroupOffsetsResult r = mock(ListConsumerGroupOffsetsResult.class);
        when(r.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(offsets));
        when(admin.listConsumerGroupOffsets(grupo)).thenReturn(r);
    }

    private AlterConsumerGroupOffsetsResult alterOk(String grupo) {
        AlterConsumerGroupOffsetsResult r = mock(AlterConsumerGroupOffsetsResult.class);
        when(r.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(admin.alterConsumerGroupOffsets(eq(grupo), anyMap())).thenReturn(r);
        return r;
    }

    private Map<TopicPartition, OffsetAndMetadata> capturarAlter(String grupo) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> captor = ArgumentCaptor.forClass(Map.class);
        verify(admin).alterConsumerGroupOffsets(eq(grupo), captor.capture());
        return captor.getValue();
    }
}
