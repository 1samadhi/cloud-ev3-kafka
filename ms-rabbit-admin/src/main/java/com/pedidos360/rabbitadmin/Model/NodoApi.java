package com.pedidos360.rabbitadmin.Model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Un elemento de GET /api/nodes (RabbitMQ 3.13).
 *
 * Un nodo APAGADO sigue apareciendo (es miembro del clúster) pero sólo con
 * name/type/running=false: todas las métricas vienen ausentes, de ahí los
 * wrappers en vez de primitivos.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodoApi(
        String name,
        /** disc | ram */
        String type,
        Boolean running,
        @JsonProperty("mem_used") Long memUsed,
        @JsonProperty("mem_limit") Long memLimit,
        @JsonProperty("mem_alarm") Boolean memAlarm,
        @JsonProperty("disk_free") Long diskFree,
        @JsonProperty("disk_free_limit") Long diskFreeLimit,
        @JsonProperty("disk_free_alarm") Boolean diskFreeAlarm,
        /** Nodos con los que ESTE nodo detectó una partición de red. Vacío = sano. */
        List<String> partitions,
        /** Milisegundos desde que arrancó el nodo. */
        Long uptime) {
}
