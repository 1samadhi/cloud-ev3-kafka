package com.kafka.pago.Config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Nombres de tópicos y grupos (app.kafka.* en application.yaml).
 *
 * Es @Component con nombre fijo para que los @KafkaListener puedan leerlo por
 * SpEL (#{@kafkaTopicosProperties...}): así ningún nombre de tópico queda
 * escrito como literal en medio de la lógica y cambiarlo es tocar sólo el yaml.
 * @Validated hace que un nombre vacío detenga el arranque en vez de fallar en
 * el primer mensaje.
 */
@Data
@Validated
@Component("kafkaTopicosProperties")
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaTopicosProperties {

    @Valid
    private Topicos topicos = new Topicos();

    @Valid
    private Grupos grupos = new Grupos();

    @Valid
    private Concurrencia concurrencia = new Concurrencia();

    @Valid
    private Reintentos reintentos = new Reintentos();

    /** Segundos que el HTTP espera la confirmación del broker antes de responder 503. */
    @Min(1)
    private int timeoutConfirmacionSegundos = 5;

    @Data
    public static class Topicos {
        @NotBlank
        private String intentosPago = "payment-attempts";
        @NotBlank
        private String resultadosPago = "pagos-resultado";
        /** Sufijo del contrato (§2.1); el de Spring por defecto es "-dlt". */
        @NotBlank
        private String sufijoDlt = ".DLT";

        public String getIntentosPagoDlt() {
            return intentosPago + sufijoDlt;
        }
    }

    @Data
    public static class Grupos {
        @NotBlank
        private String antifraude = "grupo-antifraude";
        @NotBlank
        private String dlt = "grupo-dlt-ms-pago";
    }

    @Data
    public static class Concurrencia {
        /** 3 hilos para 3 particiones: cada hilo atiende una partición (reparto por tarjeta). */
        @Min(1)
        private int antifraude = 3;
    }

    @Data
    public static class Reintentos {
        /** Contrato §3.4: 1 s, ×2, techo 10 s, 3 reintentos. Configurable para que los tests no esperen. */
        @Min(1)
        private long intervaloInicialMs = 1000;
        private double multiplicador = 2.0;
        @Min(1)
        private long intervaloMaximoMs = 10000;
        @Min(0)
        private int maximo = 3;
    }
}
