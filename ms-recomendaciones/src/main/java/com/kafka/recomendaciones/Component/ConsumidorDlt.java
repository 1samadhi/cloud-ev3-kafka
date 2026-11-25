package com.kafka.recomendaciones.Component;

import com.kafka.recomendaciones.Service.ServicioMensajesMuertos;
import com.kafka.recomendaciones.Service.ServicioMensajesMuertos.MensajeMuerto;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * grupo-dlt-recomendaciones: lee interacciones-user.DLT y catalogo-productos.DLT
 * y delega el registro de cada mensaje muerto con los headers que agrega el
 * DeadLetterPublishingRecoverer (sin ellos no se sabria de que particion/offset
 * vino ni por que murio).
 */
@Component
public class ConsumidorDlt {

    /** Un JSON gigante o binario no debe inundar el log. */
    private static final int MAX_VALOR = 500;

    private final ServicioMensajesMuertos servicio;

    public ConsumidorDlt(ServicioMensajesMuertos servicio) {
        this.servicio = servicio;
    }

    @KafkaListener(
            id = "consumidor-dlt-recomendaciones",
            topics = {"#{@kafkaTopicosProperties.dltInteracciones}", "#{@kafkaTopicosProperties.dltCatalogo}"},
            groupId = "#{@kafkaTopicosProperties.grupos.dlt}",
            containerFactory = "fabricaListenerDlt",
            clientIdPrefix = "ms-recomendaciones-dlt")
    public void consumir(ConsumerRecord<String, String> registro) {
        Headers h = registro.headers();
        String valor = registro.value();
        if (valor != null && valor.length() > MAX_VALOR) {
            valor = valor.substring(0, MAX_VALOR) + "...";
        }
        servicio.registrar(new MensajeMuerto(
                registro.topic(),
                texto(h, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                entero(h, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                largo(h, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                texto(h, KafkaHeaders.DLT_EXCEPTION_FQCN),
                texto(h, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                registro.key(),
                valor,
                System.currentTimeMillis()));
    }

    private static String texto(Headers headers, String nombre) {
        Header h = headers.lastHeader(nombre);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    // Particion y offset viajan como int/long binarios (big-endian), no como texto.
    private static Integer entero(Headers headers, String nombre) {
        Header h = headers.lastHeader(nombre);
        return h == null || h.value().length != Integer.BYTES ? null : ByteBuffer.wrap(h.value()).getInt();
    }

    private static Long largo(Headers headers, String nombre) {
        Header h = headers.lastHeader(nombre);
        return h == null || h.value().length != Long.BYTES ? null : ByteBuffer.wrap(h.value()).getLong();
    }
}
