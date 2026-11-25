package com.pedidos360.rabbitadmin.Model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Un elemento de GET /api/bindings/{vhost} (RabbitMQ 3.13).
 * source "" = binding implícito del exchange por defecto (uno por cola).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BindingApi(
        String source,
        String vhost,
        String destination,
        /** queue | exchange */
        @JsonProperty("destination_type") String destinationType,
        @JsonProperty("routing_key") String routingKey,
        Map<String, Object> arguments,
        /** Identificador del binding que exige DELETE /api/bindings/... de la API HTTP. */
        @JsonProperty("properties_key") String propertiesKey) {
}
