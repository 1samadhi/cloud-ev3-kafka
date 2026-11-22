package com.kafka.pago.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.kafka.pago.Config.KafkaTopicosProperties;
import com.kafka.pago.DTO.IntentoPagoDTO;
import com.kafka.pago.DTO.TipoPasarela;
import com.kafka.pago.Exception.KafkaNoDisponibleException;

/** El camino del 503: el productor convierte toda falta de ack en KafkaNoDisponibleException. */
class ProductorIntentosPagoTest {

    private KafkaTemplate<String, IntentoPagoDTO> plantilla;
    private ProductorIntentosPago productor;
    private final IntentoPagoDTO intento = IntentoPagoDTO.builder()
            .idIntento("i-1").idOrden("TX-1").idCliente("ana").huellaTarjeta("a".repeat(64))
            .monto(1000L).moneda("CLP").pasarela(TipoPasarela.SIMULADA).timestamp(1L).build();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        plantilla = mock(KafkaTemplate.class);
        KafkaTopicosProperties propiedades = new KafkaTopicosProperties();
        propiedades.setTimeoutConfirmacionSegundos(1);
        productor = new ProductorIntentosPago(plantilla, propiedades);
    }

    @Test
    void publicaConLaHuellaComoKeyYDevuelveParticionYOffset() throws Exception {
        SendResult<String, IntentoPagoDTO> ok = new SendResult<>(
                new ProducerRecord<>("payment-attempts", "a".repeat(64), intento),
                new RecordMetadata(new TopicPartition("payment-attempts", 1), 41, 0, 0L, 64, 100));
        when(plantilla.send(eq("payment-attempts"), eq("a".repeat(64)), any())).thenReturn(CompletableFuture.completedFuture(ok));
        SendResult<String, IntentoPagoDTO> r = productor.publicarConConfirmacion(intento);
        assertThat(r.getRecordMetadata().partition()).isEqualTo(1);
        assertThat(r.getRecordMetadata().offset()).isEqualTo(41);
    }

    @Test
    void sinAckDentroDelPlazoEsKafkaNoDisponible() {
        when(plantilla.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
        long inicio = System.nanoTime();
        assertThatThrownBy(() -> productor.publicarConConfirmacion(intento))
                .isInstanceOf(KafkaNoDisponibleException.class)
                .hasMessageContaining("1 s");
        assertThat((System.nanoTime() - inicio) / 1_000_000).isBetween(900L, 4000L);
    }

    @Test
    void envioFallidoEsKafkaNoDisponible() {
        when(plantilla.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("NOT_ENOUGH_REPLICAS")));
        assertThatThrownBy(() -> productor.publicarConConfirmacion(intento)).isInstanceOf(KafkaNoDisponibleException.class);
    }

    @Test
    void metadataInalcanzableAlEnviarEsKafkaNoDisponible() {
        // max.block.ms vencido: send() lanza en el acto en lugar de devolver un futuro.
        when(plantilla.send(anyString(), anyString(), any())).thenThrow(new KafkaException("Timeout expired while fetching topic metadata"));
        assertThatThrownBy(() -> productor.publicarConConfirmacion(intento)).isInstanceOf(KafkaNoDisponibleException.class);
    }
}
