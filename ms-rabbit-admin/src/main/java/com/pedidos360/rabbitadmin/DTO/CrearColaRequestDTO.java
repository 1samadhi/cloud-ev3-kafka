package com.pedidos360.rabbitadmin.DTO;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/**
 * POST /api/rabbit/colas.
 *
 * tipo por defecto "quorum": es el estándar del clúster (replicada en los 3
 * nodos, sobrevive a la caída de uno). durable por defecto true.
 */
public record CrearColaRequestDTO(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 255, message = "El nombre no puede superar 255 caracteres (límite de AMQP)")
        @Pattern(regexp = "^[A-Za-z0-9._:-]+$",
                message = "El nombre sólo admite letras, números, '.', '_', ':' y '-'")
        @Pattern(regexp = "^(?!amq\\.).*$",
                message = "El prefijo 'amq.' está reservado por RabbitMQ: el broker rechaza declararlo")
        String nombre,

        @Pattern(regexp = "quorum|classic", message = "El tipo de cola debe ser 'quorum' o 'classic'")
        String tipo,

        Boolean durable,

        /** x-dead-letter-exchange: a dónde van los mensajes rechazados o expirados. */
        @Size(max = 255, message = "deadLetterExchange no puede superar 255 caracteres")
        String deadLetterExchange,

        /** x-delivery-limit: entregas fallidas antes de mandar el mensaje al DLX (sólo quorum). */
        @Min(value = 1, message = "limiteEntregas debe ser al menos 1")
        Integer limiteEntregas,

        /** x-message-ttl en milisegundos. */
        @Min(value = 1, message = "ttlMensajesMs debe ser al menos 1")
        Integer ttlMensajesMs,

        /** x-max-length: mensajes máximos en la cola. */
        @Min(value = 1, message = "longitudMaxima debe ser al menos 1")
        Integer longitudMaxima) {

    public CrearColaRequestDTO {
        tipo = (tipo == null || tipo.isBlank()) ? "quorum" : tipo.trim().toLowerCase(Locale.ROOT);
        durable = durable == null ? Boolean.TRUE : durable;
        deadLetterExchange = (deadLetterExchange == null || deadLetterExchange.isBlank()) ? null : deadLetterExchange.trim();
    }

    /** Una cola quorum vive en el log Raft en disco: el broker rechaza una quorum no durable. */
    @JsonIgnore
    @AssertTrue(message = "Una cola quorum siempre es durable: no admite durable=false")
    public boolean isDurableSiEsQuorum() {
        return !esQuorum() || Boolean.TRUE.equals(durable);
    }

    /** x-delivery-limit no existe en las classic: el broker lo ignoraría en silencio. */
    @JsonIgnore
    @AssertTrue(message = "limiteEntregas (x-delivery-limit) sólo aplica a colas quorum")
    public boolean isLimiteEntregasSoloQuorum() {
        return limiteEntregas == null || esQuorum();
    }

    @JsonIgnore
    public boolean esQuorum() {
        return "quorum".equals(tipo);
    }
}
