package com.kafka.pago;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.kafka.pago.DTO.IntentoPagoDTO;
import com.kafka.pago.DTO.TipoPasarela;
import com.kafka.pago.Exception.TarjetaBloqueadaException;
import com.kafka.pago.Model.AlertaFraude;
import com.kafka.pago.Model.BloqueoTarjeta;
import com.kafka.pago.Repository.AlertaFraudeRepositorio;
import com.kafka.pago.Repository.BloqueoTarjetaRepositorio;
import com.kafka.pago.Repository.IntentoTarjetaRepositorio;
import com.kafka.pago.Service.HuellaTarjeta;
import com.kafka.pago.Service.ServicioAntifraude;
import com.kafka.pago.Service.ServicioMensajesMuertos;
import com.kafka.pago.Service.ServicioMensajesMuertos.MensajeMuerto;

/**
 * Integración real del antifraude: broker Kafka embebido (KRaft) + H2 + el
 * contexto completo de Spring. Prueba lo que los tests unitarios no pueden:
 * que el productor, el listener de grupo-antifraude, el DefaultErrorHandler y
 * el DLT están bien cableados entre sí.
 *
 * Cada prueba usa una tarjeta aleatoria (huella propia), así el orden de
 * ejecución no importa aunque compartan la base y el broker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 3, topics = {"payment-attempts", "payment-attempts.DLT", "pagos-resultado"})
@ExtendWith(OutputCaptureExtension.class)
class AntifraudeKafkaIntegracionTest {

    private static final String TOPICO = "payment-attempts";
    private static final Duration ESPERA = Duration.ofSeconds(30);

    @Autowired private MockMvc mvc;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private KafkaTemplate<String, IntentoPagoDTO> plantillaIntentosPago;
    @Autowired private KafkaListenerEndpointRegistry registro;
    @Autowired private HuellaTarjeta huellas;
    @Autowired private ServicioAntifraude servicioAntifraude;
    @Autowired private ServicioMensajesMuertos mensajesMuertos;
    @Autowired private IntentoTarjetaRepositorio intentos;
    @Autowired private BloqueoTarjetaRepositorio bloqueos;
    @Autowired private AlertaFraudeRepositorio alertas;

    /** 16 dígitos que nunca terminan en 0002 (la pasarela simulada los rechazaría). */
    private static String numeroAleatorio() {
        StringBuilder sb = new StringBuilder("4");
        for (int i = 0; i < 14; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.append('7').toString();
    }

    private static IntentoPagoDTO intento(String huella, String ultimos4, long timestamp) {
        return IntentoPagoDTO.builder()
                .idIntento(UUID.randomUUID().toString())
                .idOrden("TX-" + timestamp)
                .idCliente("cliente-it")
                .huellaTarjeta(huella)
                .ultimos4(ultimos4)
                .monto(1000L)
                .moneda("CLP")
                .pasarela(TipoPasarela.SIMULADA)
                .timestamp(timestamp)
                .build();
    }

    private void publicar(IntentoPagoDTO dto) throws Exception {
        plantillaIntentosPago.send(TOPICO, dto.getHuellaTarjeta(), dto).get();
    }

    private boolean bloqueada(String huella) {
        return bloqueos.findById(huella).map(b -> b.estaVigente(Instant.now())).orElse(false);
    }

    @Test
    void grupoAntifraudeCorreConTresHilos() {
        var contenedor = (ConcurrentMessageListenerContainer<?, ?>) registro.getListenerContainer("consumidor-antifraude");
        assertThat(contenedor).isNotNull();
        assertThat(contenedor.getConcurrency()).isEqualTo(3);
        assertThat(contenedor.getGroupId()).isEqualTo("grupo-antifraude-test");
        assertThat(registro.getListenerContainer("consumidor-dlt-ms-pago")).isNotNull();
    }

    @Test
    void cuartoIntentoDeLaMismaTarjetaEnMenosDeDiezSegundosBloqueaYRegistraAlerta() throws Exception {
        String numero = numeroAleatorio();
        String huella = huellas.deTarjeta(numero);
        String ultimos4 = HuellaTarjeta.ultimos4(numero);
        long t0 = System.currentTimeMillis() - 5_000;

        for (int i = 0; i < 3; i++) {
            publicar(intento(huella, ultimos4, t0 + i * 1_000L));
        }
        await().atMost(ESPERA).until(() -> intentos.countByHuella(huella) == 3);
        // "Más de 3": con 3 intentos en la ventana todavía no hay bloqueo ni alerta.
        assertThat(bloqueada(huella)).isFalse();
        assertThat(alertas.findByHuellaOrderByCreadaEnDesc(huella)).isEmpty();

        publicar(intento(huella, ultimos4, t0 + 3_000));
        await().atMost(ESPERA).until(() -> bloqueada(huella));

        BloqueoTarjeta bloqueo = bloqueos.findById(huella).orElseThrow();
        assertThat(bloqueo.getIntentos()).isEqualTo(4);
        assertThat(bloqueo.getUltimos4()).isEqualTo(ultimos4);
        List<AlertaFraude> registradas = alertas.findByHuellaOrderByCreadaEnDesc(huella);
        assertThat(registradas).hasSize(1);
        assertThat(registradas.get(0).getIntentos()).isEqualTo(4);
        assertThat(registradas.get(0).getIdCliente()).isEqualTo("cliente-it");
        // El paso 2 del flujo HTTP ya ve el bloqueo.
        assertThatThrownBy(() -> servicioAntifraude.verificarNoBloqueada(huella))
                .isInstanceOf(TarjetaBloqueadaException.class);
    }

    @Test
    void cuatroIntentosRepartidosEnOnceSegundosNoBloquean() throws Exception {
        String huella = huellas.deTarjeta(numeroAleatorio());
        long t0 = System.currentTimeMillis() - 20_000;
        // El 4.º (t0+11 s) mira (t0+1 s, t0+11 s]: el primero ya salió, quedan 3.
        for (long desfase : new long[] {0, 3_666, 7_333, 11_000}) {
            publicar(intento(huella, "1234", t0 + desfase));
        }
        await().atMost(ESPERA).until(() -> intentos.countByHuella(huella) == 4);
        assertThat(bloqueada(huella)).isFalse();
        assertThat(alertas.findByHuellaOrderByCreadaEnDesc(huella)).isEmpty();
    }

    @Test
    void rafagaHttpConPasarelaSimuladaBloqueaElQuintoConCodigo429SinLoguearLaTarjeta(CapturedOutput salida) throws Exception {
        String numero = numeroAleatorio();
        String escrito = numero.replaceAll("(\\d{4})(?=\\d)", "$1 "); // "4xxx xxxx xxxx xxx7"
        String huella = huellas.deTarjeta(numero);
        String cuerpo = "{\"clientId\":\"rafaga\",\"amount\":1000,\"transactionId\":\"TX-%d\",\"pasarela\":\"SIMULADA\","
                + "\"origen\":\"TIENDA\",\"tarjeta\":{\"numero\":\"" + escrito + "\"}}";

        // Los 3 primeros pasan: "más de 3 veces" significa que el 4.º infringe.
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON).content(cuerpo.formatted(i)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.estado").value("AUTORIZADO"))
                    .andExpect(jsonPath("$.ultimos4").value(HuellaTarjeta.ultimos4(numero)));
        }
        // El HTTP anticipa la decisión con los intentos que el consumidor ya
        // registró; se espera a que estén los 3 (consistencia eventual de
        // milisegundos, documentada en ServicioAntifraude.anticiparDecision).
        await().atMost(ESPERA).until(() -> intentos.countByHuella(huella) == 3);

        // El 4.º —el que infringe— recibe 429 y no se cobra...
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON).content(cuerpo.formatted(4)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(startsWith("Demasiados intentos detectados")))
                .andExpect(jsonPath("$.intentos").value(4));

        // ...pero se publicó igual, y el consumidor lo cuenta, bloquea y alerta.
        await().atMost(ESPERA).until(() -> bloqueada(huella));
        mvc.perform(post("/api/pagos/iniciar").contentType(MediaType.APPLICATION_JSON).content(cuerpo.formatted(5)))
                .andExpect(status().isTooManyRequests());

        mvc.perform(get("/api/alertas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertas[?(@.huellaTarjeta == '" + HuellaTarjeta.abreviar(huella) + "')].ultimos4")
                        .value(HuellaTarjeta.ultimos4(numero)));

        assertThat(salida.getAll()).doesNotContain(numero).doesNotContain(escrito);
        assertThat(salida.getAll()).contains("ALERTA FRAUDE");
    }

    @Test
    void payloadInvalidoVaAlDltEnLaMismaParticionYElSiguienteSeProcesaIgual() throws Exception {
        String huella = huellas.deTarjeta(numeroAleatorio());
        Map<String, Object> props = KafkaTestUtils.producerProps(broker);
        KafkaTemplate<String, String> crudo = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer()));
        try {
            // Misma key para los tres: misma partición, el válido queda DETRÁS de los dos rotos.
            int particion = crudo.send(TOPICO, huella, "{esto no es json").get().getRecordMetadata().partition();
            crudo.send(TOPICO, huella, ("{\"idIntento\":\"inv-1\",\"idOrden\":\"TX\",\"idCliente\":\"c\",\"huellaTarjeta\":\"%s\","
                    + "\"monto\":-5,\"moneda\":\"CLP\",\"pasarela\":\"SIMULADA\",\"timestamp\":1}").formatted(huella)).get();
            IntentoPagoDTO valido = intento(huella, "4321", System.currentTimeMillis());
            publicar(valido);

            // 1) La partición no quedó trabada: el válido se procesó.
            await().atMost(ESPERA).until(() -> intentos.existsByIdIntento(valido.getIdIntento()));
            assertThat(intentos.existsByIdIntento("inv-1")).isFalse();

            // 2) El ConsumidorDlt registró los dos con el contexto original.
            await().atMost(ESPERA).until(() -> muertosDe(huella).size() == 2);
            assertThat(muertosDe(huella)).allSatisfy(m -> {
                assertThat(m.topicoOriginal()).isEqualTo(TOPICO);
                assertThat(m.particionOriginal()).isEqualTo(particion);
                assertThat(m.offsetOriginal()).isNotNull();
                assertThat(m.mensajeExcepcion()).isNotBlank();
            });
            assertThat(muertosDe(huella)).anySatisfy(m -> assertThat(m.valor()).contains("esto no es json"));
            assertThat(muertosDe(huella)).anySatisfy(m -> assertThat(m.mensajeExcepcion()).contains("monto debe ser mayor a 0"));

            // 3) En el tópico .DLT quedaron en la MISMA partición que el original.
            Map<String, Object> cprops = KafkaTestUtils.consumerProps("verificador-dlt-" + UUID.randomUUID(), "false", broker);
            cprops.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            try (Consumer<String, String> consumidor = new DefaultKafkaConsumerFactory<>(cprops,
                    new StringDeserializer(), new StringDeserializer()).createConsumer()) {
                broker.consumeFromAnEmbeddedTopic(consumidor, TOPICO + ".DLT");
                List<ConsumerRecord<String, String>> propios = new ArrayList<>();
                long limite = System.currentTimeMillis() + ESPERA.toMillis();
                while (propios.size() < 2 && System.currentTimeMillis() < limite) {
                    KafkaTestUtils.getRecords(consumidor, Duration.ofSeconds(2)).forEach(r -> {
                        if (huella.equals(r.key())) {
                            propios.add(r);
                        }
                    });
                }
                assertThat(propios).hasSize(2).allSatisfy(r -> {
                    assertThat(r.partition()).isEqualTo(particion);
                    assertThat(ByteBuffer.wrap(r.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).getInt())
                            .isEqualTo(particion);
                });
            }
        } finally {
            crudo.destroy();
        }
    }

    private List<MensajeMuerto> muertosDe(String huella) {
        return mensajesMuertos.recientes().stream().filter(m -> huella.equals(m.key())).toList();
    }
}
