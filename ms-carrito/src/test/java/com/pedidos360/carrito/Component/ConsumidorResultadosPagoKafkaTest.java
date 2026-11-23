package com.pedidos360.carrito.Component;

import com.pedidos360.carrito.DTO.EstadoPago;
import com.pedidos360.carrito.DTO.Pasarela;
import com.pedidos360.carrito.DTO.ResultadoPagoDTO;
import com.pedidos360.carrito.Service.ResultadoProcesamiento;
import com.pedidos360.carrito.Service.ServicioPedidos;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integración del consumidor grupo-carrito con Kafka embebido (KRaft, sin Docker): fábricas,
 * ErrorHandlingDeserializer, validación, DefaultErrorHandler y DeadLetterPublishingRecoverer reales.
 * Los mensajes se publican como texto JSON, igual que los publica ms-pago (sin headers de tipo).
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "app.kafka.auto-arranque-listeners=true"})
@EmbeddedKafka(partitions = 3, topics = {"pagos-resultado", "pagos-resultado.DLT"})
@DirtiesContext
class ConsumidorResultadosPagoKafkaTest {

    @Autowired EmbeddedKafkaBroker broker;
    @MockitoBean ServicioPedidos servicioPedidos;

    KafkaTemplate<String, String> productor;
    Consumer<String, String> consumidorDlt;

    @BeforeEach
    void preparar() {
        Map<String, Object> props = KafkaTestUtils.producerProps(broker);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        productor = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));

        Map<String, Object> c = KafkaTestUtils.consumerProps("prueba-lector-dlt-" + System.nanoTime(), "false", broker);
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumidorDlt = new DefaultKafkaConsumerFactory<>(c, new StringDeserializer(), new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumidorDlt, "pagos-resultado.DLT");

        when(servicioPedidos.procesarResultadoPago(any())).thenReturn(ResultadoProcesamiento.PAGADO);
    }

    @AfterEach
    void cerrar() {
        consumidorDlt.close();
        productor.destroy();
    }

    private static String json(String idOrden, String estado) {
        return "{\"idOrden\":\"" + idOrden + "\",\"idCliente\":\"cliente-1\",\"estado\":\"" + estado
                + "\",\"monto\":799970,\"codigoAutorizacion\":\"1213\",\"ultimos4\":\"6623\","
                + "\"pasarela\":\"SIMULADA\",\"timestamp\":1764082800000}";
    }

    private List<ConsumerRecord<String, String>> leerDlt(String key, int minimo) {
        List<ConsumerRecord<String, String>> leidos = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            consumidorDlt.poll(Duration.ofMillis(500)).forEach(r -> {
                if (key.equals(r.key())) {
                    leidos.add(r);
                }
            });
            return leidos.size() >= minimo;
        });
        return leidos;
    }

    private static String header(ConsumerRecord<?, ?> r, String nombre) {
        var h = r.headers().lastHeader(nombre);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    @Test
    void resultadoValidoLlegaAlServicioConTodosLosCampos() {
        productor.send("pagos-resultado", "P-100", json("P-100", "AUTORIZADO"));

        verify(servicioPedidos, timeout(30_000)).procesarResultadoPago(argThat((ResultadoPagoDTO r) ->
                "P-100".equals(r.getIdOrden())
                        && r.getEstado() == EstadoPago.AUTORIZADO
                        && Long.valueOf(799_970L).equals(r.getMonto())
                        && r.getPasarela() == Pasarela.SIMULADA
                        && "1213".equals(r.getCodigoAutorizacion())
                        && "cliente-1".equals(r.getIdCliente())));
    }

    @Test
    void payloadsInvalidosVanAlDltYElSiguienteValidoSeProcesaIgual() {
        // Misma key = misma partición: prueba que el inválido no bloquea a los que vienen detrás.
        productor.send("pagos-resultado", "P-200", "esto no es json");                       // DeserializationException
        productor.send("pagos-resultado", "P-200", json("P-200", "PAGADO"));                 // enum desconocido
        productor.send("pagos-resultado", "P-200", "{\"idOrden\":\"\",\"estado\":\"RECHAZADO\"}"); // falla validación
        productor.send("pagos-resultado", "P-200", json("P-200", "RECHAZADO"));              // válido

        verify(servicioPedidos, timeout(30_000)).procesarResultadoPago(argThat((ResultadoPagoDTO r) ->
                "P-200".equals(r.getIdOrden()) && r.getEstado() == EstadoPago.RECHAZADO));

        List<ConsumerRecord<String, String>> muertos = leerDlt("P-200", 3);
        assertThat(muertos).hasSize(3);
        assertThat(muertos).allSatisfy(r ->
                assertThat(header(r, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo("pagos-resultado"));
        assertThat(muertos.get(0).value()).isEqualTo("esto no es json");
        assertThat(header(muertos.get(0), KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("DeserializationException");
        assertThat(header(muertos.get(2), KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo("com.pedidos360.carrito.Exception.PayloadInvalidoException");
        // El DLT conserva la partición del original (el DLT se crea con las mismas particiones).
        assertThat(muertos).extracting(ConsumerRecord::partition).containsOnly(muertos.get(0).partition());

        // Ningún payload inválido llegó al servicio.
        verify(servicioPedidos, never()).procesarResultadoPago(argThat((ResultadoPagoDTO r) ->
                r.getIdOrden() == null || r.getIdOrden().isBlank()));
    }
}
