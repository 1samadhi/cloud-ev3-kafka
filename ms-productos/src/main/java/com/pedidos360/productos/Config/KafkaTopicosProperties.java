package com.pedidos360.productos.Config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Nombres de tópico y parámetros de publicación leídos de {@code app.kafka.*}.
 *
 * Se registra como {@code @Component} para que el bean se llame
 * {@code kafkaTopicosProperties} y pueda usarse en SpEL igual que en el resto de los
 * servicios. {@code @Validated}: si falta un nombre de tópico el servicio no arranca, en
 * vez de fallar recién al primer envío.
 *
 * ms-productos sólo PRODUCE (§1), por eso no hay {@code app.kafka.grupos}.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaTopicosProperties {

    @Valid
    private Topicos topicos = new Topicos();

    /** Publicar el catálogo completo en ApplicationReadyEvent (§7.2). */
    private boolean publicarCatalogoAlArrancar = true;

    /** Tope de bloqueo de send() esperando metadatos (max.block.ms). */
    @Min(100)
    private long maxBloqueoMs = 3000;

    @Getter
    @Setter
    public static class Topicos {
        /** Clickstream; key = usuarioId. */
        @NotBlank
        private String interacciones = "interacciones-user";
        /** Tópico compactado con el último snapshot de cada producto; key = productoId. */
        @NotBlank
        private String catalogo = "catalogo-productos";
    }
}
