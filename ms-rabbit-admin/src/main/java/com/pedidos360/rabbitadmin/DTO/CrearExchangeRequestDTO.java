package com.pedidos360.rabbitadmin.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/** POST /api/rabbit/exchanges. durable por defecto true; autoEliminar e interno por defecto false. */
public record CrearExchangeRequestDTO(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 255, message = "El nombre no puede superar 255 caracteres (límite de AMQP)")
        @Pattern(regexp = "^[A-Za-z0-9._:-]+$",
                message = "El nombre sólo admite letras, números, '.', '_', ':' y '-'")
        @Pattern(regexp = "^(?!amq\\.).*$",
                message = "El prefijo 'amq.' está reservado por RabbitMQ: el broker rechaza declararlo")
        String nombre,

        @NotBlank(message = "El tipo de exchange es obligatorio")
        @Pattern(regexp = "direct|topic|fanout|headers",
                message = "El tipo de exchange debe ser direct, topic, fanout o headers")
        String tipo,

        Boolean durable,

        Boolean autoEliminar,

        /** interno: sólo recibe mensajes de otros exchanges, no de publicadores. */
        Boolean interno,

        /** alternate-exchange: recibe lo que este exchange no pudo rutear. */
        @Size(max = 255, message = "exchangeAlterno no puede superar 255 caracteres")
        String exchangeAlterno) {

    public CrearExchangeRequestDTO {
        tipo = tipo == null ? null : tipo.trim().toLowerCase(Locale.ROOT);
        durable = durable == null ? Boolean.TRUE : durable;
        autoEliminar = autoEliminar == null ? Boolean.FALSE : autoEliminar;
        interno = interno == null ? Boolean.FALSE : interno;
        exchangeAlterno = (exchangeAlterno == null || exchangeAlterno.isBlank()) ? null : exchangeAlterno.trim();
    }
}
