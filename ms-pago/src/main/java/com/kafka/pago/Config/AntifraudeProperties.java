package com.kafka.pago.Config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Parámetros de la regla antifraude (app.antifraude.*). Ventana y umbral son
 * configurables porque son decisiones de negocio: si mañana el comercio quiere
 * "más de 5 en 30 s" se cambia el yaml, no el código.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.antifraude")
public class AntifraudeProperties {

    /** Largo de la ventana deslizante. */
    @Min(1)
    private int ventanaSegundos = 10;

    /** "Más de N veces": el intento N+1 dentro de la ventana es el que infringe. */
    @Min(1)
    private int umbral = 3;

    @Min(1)
    private int bloqueoMinutos = 30;

    /**
     * Secreto que se concatena al número antes del hash. Sin pepper, una huella
     * filtrada se revierte probando los ~10^10 números válidos de un BIN; con
     * pepper hace falta además robar este valor.
     */
    @NotBlank
    private String pepper = "pedidos360-dev";

    /** Cuánto se conservan los intentos: la ventana usa 10 s, el resto es para auditoría corta. */
    @Min(1)
    private int retencionIntentosMinutos = 60;
}
