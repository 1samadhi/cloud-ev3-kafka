package com.kafka.pago.Config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebPayConfig {

    @PostConstruct
    public void initWebpay() {
        System.out.println("Configuración de Webpay lista para SDK 6.x");
    }
}
