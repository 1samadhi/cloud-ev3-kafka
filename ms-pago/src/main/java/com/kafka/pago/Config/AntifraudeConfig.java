package com.kafka.pago.Config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.kafka.pago.Service.VentanaAntifraude;

/**
 * Arma las piezas de dominio puras con la configuración.
 *
 * El Clock es un bean para que las pruebas puedan fijar la hora: una regla
 * temporal que lee LocalDateTime.now() por dentro no se puede probar sin
 * dormir el hilo.
 * @EnableScheduling activa la limpieza periódica de intento_tarjeta.
 */
@Configuration
@EnableScheduling
public class AntifraudeConfig {

    @Bean
    public Clock reloj() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public VentanaAntifraude ventanaAntifraude(AntifraudeProperties propiedades) {
        return new VentanaAntifraude(Duration.ofSeconds(propiedades.getVentanaSegundos()), propiedades.getUmbral());
    }
}
