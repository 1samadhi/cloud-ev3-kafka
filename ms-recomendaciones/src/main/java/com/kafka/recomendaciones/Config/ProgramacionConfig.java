package com.kafka.recomendaciones.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Tareas periodicas (difusion por WebSocket cada 2 s, snapshot a DynamoDB cada
 * 5 s) y el reloj compartido.
 *
 * El reloj es un bean para que las ventanas deslizantes y el decaimiento temporal
 * se prueben moviendo el tiempo a voluntad, sin Thread.sleep.
 */
@Configuration
@EnableScheduling
public class ProgramacionConfig {

    @Bean
    public Clock reloj() {
        return Clock.systemUTC();
    }
}
