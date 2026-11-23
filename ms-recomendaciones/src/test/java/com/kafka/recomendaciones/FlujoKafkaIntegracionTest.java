package com.kafka.recomendaciones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafka.recomendaciones.Component.ProductorInteracciones;
import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.ProductoEnriquecidoDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Repository.HistorialEventosRepository;
import com.kafka.recomendaciones.Repository.RecomendacionesUsuarioRepository;
import com.kafka.recomendaciones.Service.ServicioMensajesMuertos;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo completo con Kafka embebido (KRaft) y repositorios en memoria: sin
 * Docker, sin DynamoDB. Prueba los dos grupos sobre el mismo topico, el DLT,
 * la API REST y el WebSocket con el servidor real (puerto aleatorio).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 3, topics = {"interacciones-user", "interacciones-user.DLT",
        "catalogo-productos", "catalogo-productos.DLT"})
class FlujoKafkaIntegracionTest {

    private static final String TOPICO = "interacciones-user";
    private static final String DLT = "interacciones-user.DLT";

    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private KafkaListenerEndpointRegistry registro;
    @Autowired private ProductorInteracciones productor;
    @Autowired private HistorialEventosRepository historial;
    @Autowired private RecomendacionesUsuarioRepository recomendaciones;
    @Autowired private ServicioMensajesMuertos mensajesMuertos;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @LocalServerPort private int puerto;

    /**
     * grupo-dashboard usa offset latest: si se publica antes de que tenga sus
     * particiones asignadas, nunca ve el mensaje. Se espera la asignacion de todos.
     */
    @BeforeEach
    void esperarAsignaciones() {
        for (MessageListenerContainer c : registro.getListenerContainers()) {
            int topicos = c.getContainerProperties().getTopics().length;
            ContainerTestUtils.waitForAssignment(c, topicos * broker.getPartitionsPerTopic());
        }
    }

    @Test
    void gruposConLaConcurrenciaDelContrato() {
        assertThat(registro.getListenerContainer("consumidor-recomendaciones").getGroupId())
                .isEqualTo("grupo-recomendaciones-test");
        assertThat(registro.getListenerContainer("consumidor-dashboard").getGroupId())
                .isEqualTo("grupo-dashboard-test");
        assertThat(((org.springframework.kafka.listener.ConcurrentMessageListenerContainer<?, ?>)
                registro.getListenerContainer("consumidor-recomendaciones")).getConcurrency()).isEqualTo(3);
        assertThat(((org.springframework.kafka.listener.ConcurrentMessageListenerContainer<?, ?>)
                registro.getListenerContainer("consumidor-dashboard")).getConcurrency()).isEqualTo(1);
    }

    @Test
    void invalidosVanAlDltYElSiguienteValidoSeProcesaIgual() throws Exception {
        String usuario = "u-kafka-" + UUID.randomUUID();
        KafkaTemplate<String, String> crudo = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                KafkaTestUtils.producerProps(broker), new StringSerializer(), new StringSerializer()));

        // Misma key = misma particion: el valido queda DETRAS de los dos rotos.
        crudo.send(TOPICO, usuario, "{esto no es json").get(10, TimeUnit.SECONDS);
        crudo.send(TOPICO, usuario, "{\"idEvento\":\"x\",\"tipoEvento\":\"VER_PRODUCTO\",\"timestamp\":1}")
                .get(10, TimeUnit.SECONDS); // JSON valido pero sin usuarioId ni productoId
        productor.publicar(Soporte.evento(usuario, TipoEvento.VER_PRODUCTO, "item_107", System.currentTimeMillis()))
                .get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(historial.ultimos(usuario, 10)).hasSize(1);
            Optional<List<ProductoEnriquecidoDTO>> top = recomendaciones.obtener(usuario);
            assertThat(top).isPresent();
            assertThat(top.get()).anySatisfy(p -> {
                assertThat(p.getItemId()).isEqualTo("item_108");
                assertThat(p.getMotivo()).isEqualTo("Porque viste WH-1000XM5");
            });
        });

        List<ConsumerRecord<String, String>> enDlt = leerDlt(usuario, 2);
        assertThat(enDlt).hasSize(2);
        for (ConsumerRecord<String, String> r : enDlt) {
            assertThat(texto(r, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPICO);
            assertThat(texto(r, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNotBlank();
        }
        assertThat(enDlt.get(0).value()).isEqualTo("{esto no es json"); // bytes originales intactos
        assertThat(texto(enDlt.get(1), KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("usuarioId es obligatorio");

        // El consumidor del DLT los registro (y el dashboard NO los duplico en el DLT).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mensajesMuertos.ultimos()).filteredOn(m -> usuario.equals(m.key())).hasSize(2));
    }

    @Test
    void postViejoDeLaConsolaRecalculaRecomendacionesYMetricas() throws Exception {
        String usuario = "u-http-" + UUID.randomUUID();
        String cuerpo = mapper.writeValueAsString(Map.of("clientId", usuario, "itemId", "item_101",
                "action", "purchase", "category", "electronica", "productType", "celular"));

        mvc.perform(post("/api/interacciones").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.tipoEvento").value("COMPRA"))
                .andExpect(jsonPath("$.usuarioId").value(usuario));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(get("/api/recomendaciones").param("userId", usuario))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(5))
                        .andExpect(jsonPath("$[0].itemId").value("item_102"))
                        .andExpect(jsonPath("$[0].motivo").value("Porque compraste Galaxy S24"))
                        .andExpect(jsonPath("$[?(@.itemId == 'item_101')]").isEmpty()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(get("/api/metricas"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.tipo").value("METRICAS"))
                        .andExpect(jsonPath("$.porTipo.COMPRA").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                        .andExpect(jsonPath("$.totalInteracciones").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                        .andExpect(jsonPath("$.productosMasVistos").isArray())
                        .andExpect(jsonPath("$.serieMinutos.length()").value(15)));
    }

    @Test
    void validacionHttp() throws Exception {
        mvc.perform(post("/api/interacciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"c\",\"itemId\":\"item_101\",\"action\":\"volar\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Solicitud invalida"));
        mvc.perform(post("/api/interacciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"item_101\",\"action\":\"view\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(org.hamcrest.Matchers.containsString("clientId")));
        mvc.perform(post("/api/interacciones/simular").param("cantidad", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/recomendaciones").param("userId", " "))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/interacciones/simular").param("cantidad", "12").param("usuarioId", "demo-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicados").value(12))
                .andExpect(jsonPath("$.usuarios[0]").value("demo-x"));
    }

    @Test
    void webSocketEmpujaElSnapshotYRechazaOrigenesAjenos() throws Exception {
        StandardWebSocketClient cliente = new StandardWebSocketClient();
        BlockingQueue<String> recibidos = new LinkedBlockingQueue<>();
        WebSocketHttpHeaders permitido = new WebSocketHttpHeaders();
        permitido.setOrigin("http://localhost:5173");
        URI uri = URI.create("ws://localhost:" + puerto + "/ws/metricas");

        WebSocketSession sesion = cliente.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage m) {
                recibidos.add(m.getPayload());
            }
        }, permitido, uri).get(10, TimeUnit.SECONDS);
        try {
            String primero = recibidos.poll(10, TimeUnit.SECONDS);   // inmediato al conectar
            String segundo = recibidos.poll(10, TimeUnit.SECONDS);   // difusion programada
            assertThat(primero).isNotNull();
            assertThat(segundo).isNotNull();
            JsonNode json = mapper.readTree(primero);
            assertThat(json.get("tipo").asText()).isEqualTo("METRICAS");
            for (String campo : List.of("timestamp", "interaccionesPorMinuto", "serieMinutos", "topProductos",
                    "usuariosActivos", "embudo", "porTipo", "totalInteracciones", "productosMasVistos")) {
                assertThat(json.has(campo)).as(campo).isTrue();
            }
        } finally {
            sesion.close();
        }

        WebSocketHttpHeaders ajeno = new WebSocketHttpHeaders();
        ajeno.setOrigin("http://sitio-malicioso.example");
        Throwable error = org.assertj.core.api.Assertions.catchThrowable(() ->
                cliente.execute(new TextWebSocketHandler(), ajeno, uri).get(10, TimeUnit.SECONDS));
        assertThat(error).as("el handshake desde un origen ajeno debe fallar").isNotNull();
        Throwable raiz = error;
        while (raiz.getCause() != null) raiz = raiz.getCause();
        assertThat(raiz.getMessage()).contains("403");
    }

    // ------------------------------------------------------------------

    private List<ConsumerRecord<String, String>> leerDlt(String key, int esperados) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("verificador-dlt-" + UUID.randomUUID(), "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> encontrados = new ArrayList<>();
        try (Consumer<String, String> c = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(c, DLT);
            long limite = System.currentTimeMillis() + 15_000;
            // Se sigue leyendo 3 s despues de juntar los esperados: si el dashboard
            // tambien publicara en el DLT, aparecerian duplicados y la prueba fallaria.
            long hastaCuando = Long.MAX_VALUE;
            while (System.currentTimeMillis() < Math.min(limite, hastaCuando)) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(300))) {
                    if (key.equals(r.key())) {
                        encontrados.add(r);
                    }
                }
                if (encontrados.size() >= esperados && hastaCuando == Long.MAX_VALUE) {
                    hastaCuando = System.currentTimeMillis() + 3_000;
                }
            }
        }
        encontrados.sort((a, b) -> Long.compare(a.offset(), b.offset()));
        return encontrados;
    }

    private static String texto(ConsumerRecord<?, ?> r, String header) {
        var h = r.headers().lastHeader(header);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
