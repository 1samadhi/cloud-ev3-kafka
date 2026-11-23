package com.pedidos360.carrito.Component;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Registro de mensajes muertos (grupo-dlt-carrito, §3.4). El payload se lee como texto porque
 * puede ser justamente el JSON inválido que causó el envío al DLT. No se reprocesa: el objetivo
 * es dejar evidencia con el origen exacto (tópico/partición/offset) y la causa.
 */
@Slf4j
@Component
public class ConsumidorDlt {

    /** Tope del payload en el log: un mensaje enorme no debe inundar los logs. */
    static final int MAX_PAYLOAD_LOG = 500;

    @KafkaListener(id = "consumidor-dlt-carrito",
            topics = "#{@kafkaTopicosProperties.topicoDltPagosResultado}",
            groupId = "#{@kafkaTopicosProperties.grupos.dlt}",
            containerFactory = "dltListenerFactory",
            concurrency = "1")
    public void recibir(ConsumerRecord<String, String> registro) {
        log.error("Mensaje en DLT: tópico={} partición={} offset={} key={} | origen tópico={} partición={} offset={} "
                        + "| excepción={} mensaje={} | payload={}",
                registro.topic(), registro.partition(), registro.offset(), registro.key(),
                texto(registro, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                entero(registro, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                largo(registro, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                texto(registro, KafkaHeaders.DLT_EXCEPTION_FQCN),
                texto(registro, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                recortar(registro.value()));
    }

    static String texto(ConsumerRecord<?, ?> registro, String nombre) {
        Header h = registro.headers().lastHeader(nombre);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /** DeadLetterPublishingRecoverer escribe la partición original como int de 4 bytes (big-endian). */
    static Integer entero(ConsumerRecord<?, ?> registro, String nombre) {
        Header h = registro.headers().lastHeader(nombre);
        return h == null || h.value() == null || h.value().length != Integer.BYTES ? null : ByteBuffer.wrap(h.value()).getInt();
    }

    /** ... y el offset original como long de 8 bytes. */
    static Long largo(ConsumerRecord<?, ?> registro, String nombre) {
        Header h = registro.headers().lastHeader(nombre);
        return h == null || h.value() == null || h.value().length != Long.BYTES ? null : ByteBuffer.wrap(h.value()).getLong();
    }

    static String recortar(String payload) {
        if (payload == null || payload.length() <= MAX_PAYLOAD_LOG) {
            return payload;
        }
        return payload.substring(0, MAX_PAYLOAD_LOG) + "…(" + payload.length() + " caracteres)";
    }
}
