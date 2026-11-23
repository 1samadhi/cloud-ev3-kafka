package com.kafka.recomendaciones.Config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Nombres de topicos, grupos y parametros de reintento, leidos de application.yaml.
 *
 * Se registra como @Component (y no con @EnableConfigurationProperties) para que
 * el bean se llame exactamente "kafkaTopicosProperties": los @KafkaListener lo
 * referencian por nombre en SpEL (#{@kafkaTopicosProperties...}). Asi ningun
 * nombre de topico queda escrito en medio de la logica.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaTopicosProperties {

    private Topicos topicos = new Topicos();
    private Grupos grupos = new Grupos();
    private Concurrencia concurrencia = new Concurrencia();
    private Reintentos reintentos = new Reintentos();

    @Data
    public static class Topicos {
        private String interacciones = "interacciones-user";
        private String catalogo = "catalogo-productos";
        private String sufijoDlt = ".DLT";
    }

    @Data
    public static class Grupos {
        private String recomendaciones = "grupo-recomendaciones";
        private String dashboard = "grupo-dashboard";
        private String catalogo = "grupo-catalogo-recomendaciones";
        private String dlt = "grupo-dlt-recomendaciones";
    }

    /** Hilos por grupo (ver application.yaml y §2.2 para el por que de cada valor). */
    @Data
    public static class Concurrencia {
        private int recomendaciones = 3;
        private int dashboard = 1;
        private int catalogo = 1;
    }

    @Data
    public static class Reintentos {
        private long intervaloInicialMs = 1000;
        private double multiplicador = 2.0;
        private long intervaloMaximoMs = 10000;
        private int maximo = 3;
    }

    /** Topico de mensajes muertos de un topico dado (misma convencion que el script de infra). */
    public String dltDe(String topico) {
        return topico + topicos.getSufijoDlt();
    }

    /** interacciones-user.DLT; expuesto como propiedad para el SpEL del ConsumidorDlt. */
    public String getDltInteracciones() {
        return dltDe(topicos.getInteracciones());
    }

    /** catalogo-productos.DLT. */
    public String getDltCatalogo() {
        return dltDe(topicos.getCatalogo());
    }
}
