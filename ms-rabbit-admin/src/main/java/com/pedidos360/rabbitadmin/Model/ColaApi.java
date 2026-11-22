package com.pedidos360.rabbitadmin.Model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Un elemento de GET /api/queues/{vhost} tal como lo devuelve RabbitMQ 3.13.
 *
 * ignoreUnknown: la respuesta real trae decenas de campos (garbage_collection,
 * backing_queue_status, *_details...) que no interesan, y cambian entre
 * versiones. Los contadores son wrappers (Long/Integer) y no primitivos porque
 * una cola recién declarada todavía no tiene estadísticas y la API los OMITE:
 * hay que distinguir "sin dato" de "cero".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ColaApi(
        String name,
        String vhost,
        /** quorum | classic | stream */
        String type,
        Boolean durable,
        @JsonProperty("auto_delete") Boolean autoDelete,
        Boolean exclusive,
        Map<String, Object> arguments,
        Long messages,
        @JsonProperty("messages_ready") Long messagesReady,
        @JsonProperty("messages_unacknowledged") Long messagesUnacknowledged,
        Integer consumers,
        /** running | idle | minority (quorum sin mayoría) | down ... */
        String state,
        /** classic: nodo que la aloja. */
        String node,
        /** quorum: líder Raft actual. */
        String leader,
        /** quorum: nodos con réplica. */
        List<String> members,
        /** quorum: réplicas que están respondiendo. */
        List<String> online,
        String policy) {
}
