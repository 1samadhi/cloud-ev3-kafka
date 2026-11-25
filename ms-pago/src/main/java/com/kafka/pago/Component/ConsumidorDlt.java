package com.kafka.pago.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import com.kafka.pago.Service.ServicioMensajesMuertos;
import com.kafka.pago.Service.ServicioMensajesMuertos.MensajeMuerto;

import lombok.RequiredArgsConstructor;

/**
 * Lee payment-attempts.DLT (grupo-dlt-ms-pago) y registra cada mensaje muerto
 * con los headers que agrega el DeadLetterPublishingRecoverer: sin ellos no se
 * sabría de qué partición/offset vino ni por qué murió.
 */
@Component
@RequiredArgsConstructor
public class ConsumidorDlt {

    /** Un JSON gigante o binario no debe inundar el log. */
    private static final int MAX_VALOR = 500;

    private final ServicioMensajesMuertos servicioMensajesMuertos;

    @KafkaListener(
            id = "consumidor-dlt-ms-pago",
            topics = "#{@kafkaTopicosProperties.topicos.intentosPagoDlt}",
            groupId = "#{@kafkaTopicosProperties.grupos.dlt}",
            containerFactory = "fabricaDlt")
    public void consumir(ConsumerRecord<String, String> registro) {
        Headers headers = registro.headers();
        String valor = registro.value();
        if (valor != null && valor.length() > MAX_VALOR) {
            valor = valor.substring(0, MAX_VALOR) + "…";
        }
        servicioMensajesMuertos.registrar(new MensajeMuerto(
                texto(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                entero(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                largo(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                texto(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                registro.key(),
                valor,
                Instant.now()));
    }

    private static String texto(Headers headers, String nombre) {
        Header h = headers.lastHeader(nombre);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    // Partición y offset viajan como int/long binarios (big-endian), no como texto.
    private static Integer entero(Headers headers, String nombre) {
        Header h = headers.lastHeader(nombre);
        return h == null || h.value().length != Integer.BYTES ? null : ByteBuffer.wrap(h.value()).getInt();
    }

    private static Long largo(Headers headers, String nombre) {
        Header h = headers.lastHeader(nombre);
        return h == null || h.value().length != Long.BYTES ? null : ByteBuffer.wrap(h.value()).getLong();
    }
}
