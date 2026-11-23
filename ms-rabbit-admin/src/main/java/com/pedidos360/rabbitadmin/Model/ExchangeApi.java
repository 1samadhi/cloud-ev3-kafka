package com.pedidos360.rabbitadmin.Model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Un elemento de GET /api/exchanges/{vhost} (RabbitMQ 3.13). El exchange por defecto tiene name "". */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExchangeApi(
        String name,
        String vhost,
        String type,
        Boolean durable,
        @JsonProperty("auto_delete") Boolean autoDelete,
        Boolean internal,
        Map<String, Object> arguments) {
}
