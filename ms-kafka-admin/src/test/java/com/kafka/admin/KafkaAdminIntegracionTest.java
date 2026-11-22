package com.kafka.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Prueba de punta a punta contra un broker Kafka REAL (KRaft embebido, sin
 * Docker): HTTP -> controller -> servicio -> AdminClient -> broker.
 *
 * Los tests con mocks prueban las reglas; éste prueba que lo que se le pide a
 * Kafka es algo que Kafka acepta y que surte efecto: la config cambia de
 * verdad, las particiones aumentan de verdad y los offsets se mueven de verdad.
 *
 * Un solo broker, así que RF máximo 1 (y min.insync.replicas máximo 1).
 */
@SpringBootTest(properties = "app.kafka-admin.timeout-ms=20000")
@AutoConfigureMockMvc
@EmbeddedKafka(count = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers", brokerProperties = {
        "offsets.topic.replication.factor=1",
        "offsets.topic.num.partitions=1",
        "transaction.state.log.replication.factor=1",
        "transaction.state.log.min.isr=1",
        // Sin la espera de 3 s del primer rebalanceo: el test no la necesita.
        "group.initial.rebalance.delay.ms=0",
        "auto.create.topics.enable=false"
})
class KafkaAdminIntegracionTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void configuracionYParticionesCambianDeVerdadEnElBroker() throws Exception {
        mvc.perform(post("/api/topicos").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nombre":"it-config","particiones":1,"replicas":1,
                         "configs":{"compression.type":"lz4","segment.bytes":"268435456"}}"""))
                .andExpect(status().isCreated());

        JsonNode config = leer(mvc.perform(get("/api/topicos/it-config/configuracion")).andExpect(status().isOk()).andReturn());
        assertThat(config.at("/sobrescritas/compression.type").asText()).isEqualTo("lz4");
        assertThat(config.at("/sobrescritas/min.insync.replicas").asText())
                .as("con RF 1 el min ISR por defecto baja a 1").isEqualTo("1");

        // SET de dos claves y DELETE de otra en la misma llamada incremental.
        JsonNode cambiada = leer(mvc.perform(put("/api/topicos/it-config/configuracion")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"establecer":{"retention.ms":"86400000","cleanup.policy":"compact, delete"},
                                 "restablecer":["compression.type"]}"""))
                .andExpect(status().isOk()).andReturn());
        assertThat(cambiada.at("/sobrescritas/retention.ms").asText()).isEqualTo("86400000");
        assertThat(cambiada.at("/sobrescritas/cleanup.policy").asText()).isEqualTo("compact,delete");
        assertThat(cambiada.get("sobrescritas").has("compression.type"))
                .as("restablecida = vuelve al valor heredado").isFalse();
        // Lo que no se tocó se conserva (por eso incrementalAlterConfigs y no alterConfigs).
        assertThat(cambiada.at("/sobrescritas/segment.bytes").asText()).isEqualTo("268435456");

        // Reglas que el broker aceptaría pero el proyecto no.
        mvc.perform(put("/api/topicos/it-config/configuracion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"establecer\":{\"min.insync.replicas\":\"2\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle", containsString("factor de replicación")));
        mvc.perform(put("/api/topicos/it-config/configuracion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"establecer\":{\"unclean.leader.election.enable\":\"true\"}}"))
                .andExpect(status().isBadRequest());

        // Particiones: 1 -> 3 funciona; 3 -> 2 se rechaza; 3 -> 3 también.
        mvc.perform(post("/api/topicos/it-config/particiones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"particiones\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.particionesAnteriores").value(1))
                .andExpect(jsonPath("$.particionesActuales").value(3));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/topicos/it-config")).andExpect(jsonPath("$.particiones").value(3)));
        mvc.perform(post("/api/topicos/it-config/particiones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"particiones\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle", containsString("no permite reducir")));
        mvc.perform(post("/api/topicos/it-config/particiones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"particiones\":3}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/topicos/no-existe/configuracion")).andExpect(status().isNotFound());
        mvc.perform(post("/api/topicos/no-existe/particiones").contentType(MediaType.APPLICATION_JSON)
                .content("{\"particiones\":3}")).andExpect(status().isNotFound());
    }

    @Test
    void consumerGroupConMiembrosNoSeTocaYVacioSeReseteaYSeBorra() throws Exception {
        mvc.perform(post("/api/topicos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"it-offsets\",\"particiones\":2,\"replicas\":1}"))
                .andExpect(status().isCreated());
        producir("it-offsets", 5, 5); // 5 mensajes en cada partición

        String grupo = "it-grupo";
        try (KafkaConsumer<String, String> consumidor = consumidor(grupo)) {
            consumidor.subscribe(List.of("it-offsets"));
            int leidos = 0;
            long limite = System.currentTimeMillis() + 30_000;
            while (leidos < 10 && System.currentTimeMillis() < limite) {
                ConsumerRecords<String, String> r = consumidor.poll(Duration.ofMillis(500));
                leidos += r.count();
            }
            assertThat(leidos).isEqualTo(10);
            consumidor.commitSync();

            // Con el consumidor vivo: el detalle lo muestra con sus 2 particiones...
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    mvc.perform(get("/api/consumer-groups/" + grupo))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.estado").value("Stable"))
                            .andExpect(jsonPath("$.cantidadMiembros").value(1))
                            .andExpect(jsonPath("$.miembros[0].asignaciones.length()").value(2))
                            .andExpect(jsonPath("$.coordinador.id").isNumber()));

            // ...y ni reset ni borrado están permitidos (409).
            mvc.perform(post("/api/consumer-groups/" + grupo + "/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"topico\":\"it-offsets\",\"estrategia\":\"EARLIEST\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detalle", containsString("miembro(s) activo(s)")));
            mvc.perform(delete("/api/consumer-groups/" + grupo)).andExpect(status().isConflict());
        }
        // close() envía LeaveGroup: el grupo queda vacío sin esperar session.timeout.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mvc.perform(get("/api/consumer-groups/" + grupo)).andExpect(jsonPath("$.estado").value("Empty")));

        mvc.perform(get("/api/consumer-groups/" + grupo + "/lag")).andExpect(jsonPath("$.lagTotal").value(0));

        // EARLIEST: vuelve a 0 en ambas particiones -> lag 10.
        mvc.perform(post("/api/consumer-groups/" + grupo + "/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topico\":\"it-offsets\",\"estrategia\":\"EARLIEST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.particiones[0].offsetAnterior").value(5))
                .andExpect(jsonPath("$.particiones[0].offsetNuevo").value(0))
                .andExpect(jsonPath("$.particiones[1].offsetNuevo").value(0));
        mvc.perform(get("/api/consumer-groups/" + grupo + "/lag")).andExpect(jsonPath("$.lagTotal").value(10));

        // OFFSET exacto en una sola partición, y uno fuera de rango que se ajusta.
        mvc.perform(post("/api/consumer-groups/" + grupo + "/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topico\":\"it-offsets\",\"estrategia\":\"OFFSET\",\"valor\":3,\"particiones\":[0]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.particiones.length()").value(1))
                .andExpect(jsonPath("$.particiones[0].offsetNuevo").value(3));
        mvc.perform(post("/api/consumer-groups/" + grupo + "/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topico\":\"it-offsets\",\"estrategia\":\"OFFSET\",\"valor\":99,\"particiones\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.particiones[0].offsetNuevo").value(5))
                .andExpect(jsonPath("$.particiones[0].ajustado").value(true));
        // p0 en 3 (lag 2) + p1 en 5 (lag 0)
        mvc.perform(get("/api/consumer-groups/" + grupo + "/lag")).andExpect(jsonPath("$.lagTotal").value(2));

        // TIMESTAMP en el futuro: no hay mensajes posteriores -> al final.
        mvc.perform(post("/api/consumer-groups/" + grupo + "/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topico\":\"it-offsets\",\"estrategia\":\"TIMESTAMP\",\"valor\":4102444800000}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/consumer-groups/" + grupo + "/lag")).andExpect(jsonPath("$.lagTotal").value(0));

        // Borrado del grupo vacío.
        mvc.perform(delete("/api/consumer-groups/" + grupo)).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/consumer-groups/" + grupo)).andExpect(status().isNotFound()));
        mvc.perform(post("/api/consumer-groups/" + grupo + "/offsets/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topico\":\"it-offsets\",\"estrategia\":\"LATEST\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void configuracionDeBrokerRealYBrokerInexistente() throws Exception {
        JsonNode cluster = leer(mvc.perform(get("/api/cluster")).andExpect(status().isOk()).andReturn());
        int id = cluster.at("/brokers/0/id").asInt();

        JsonNode config = leer(mvc.perform(get("/api/brokers/" + id + "/configuracion"))
                .andExpect(status().isOk()).andReturn());
        assertThat(config.get("tipo").asText()).isEqualTo("BROKER");
        assertThat(config.get("entradas").size()).isGreaterThan(50);
        // offsets.topic.replication.factor=1 se fijó en el arranque del broker: debe salir como sobrescrita.
        assertThat(config.at("/sobrescritas/offsets.topic.replication.factor").asText()).isEqualTo("1");

        mvc.perform(get("/api/brokers/999/configuracion")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ auxiliares

    private JsonNode leer(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void producir(String topico, int enP0, int enP1) throws Exception {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> p = new KafkaProducer<>(props)) {
            for (int i = 0; i < enP0; i++) {
                p.send(new ProducerRecord<>(topico, 0, "k" + i, "v" + i)).get();
            }
            for (int i = 0; i < enP1; i++) {
                p.send(new ProducerRecord<>(topico, 1, "k" + i, "v" + i)).get();
            }
        }
    }

    private KafkaConsumer<String, String> consumidor(String grupo) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, grupo,
                ConsumerConfig.CLIENT_ID_CONFIG, "it-consumidor",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
