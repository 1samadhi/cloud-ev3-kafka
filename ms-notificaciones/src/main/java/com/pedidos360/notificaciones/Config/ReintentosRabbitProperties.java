package com.pedidos360.notificaciones.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Política de reintento stateless del consumidor (§9: 3 intentos, 1 s, x2).
 * Configurable para que los tests la ejecuten en milisegundos.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rabbit.reintentos")
public class ReintentosRabbitProperties {

    /** Intentos TOTALES (el primero + 2 reintentos). */
    private int maxIntentos = 3;

    private long intervaloInicialMs = 1000;

    private double multiplicador = 2.0;

    private long intervaloMaximoMs = 10000;
}
