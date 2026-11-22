package com.pedidos360.carrito.Config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Nombres de tópicos y grupos (app.kafka.*). Se registra con nombre fijo para poder usarlo en
 * SpEL desde los @KafkaListener ("#{@kafkaTopicosProperties...}"): así ningún nombre de tópico
 * queda escrito en la lógica y cambiarlo es tocar sólo el YAML o una variable de entorno.
 */
@Data
@Component("kafkaTopicosProperties")
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaTopicosProperties {

    private Topicos topicos = new Topicos();
    private Grupos grupos = new Grupos();
    private Concurrencia concurrencia = new Concurrencia();

    /** Convención de DeadLetterPublishingRecoverer y de crear-topicos.sh. */
    private String sufijoDlt = ".DLT";

    /** En tests sin broker se apaga para que los contenedores no intenten conectarse. */
    private boolean autoArranqueListeners = true;

    public String getTopicoDltPagosResultado() {
        return topicos.getPagosResultado() + sufijoDlt;
    }

    @Data
    public static class Topicos {
        private String interacciones = "interacciones-user";
        private String pagosResultado = "pagos-resultado";
    }

    @Data
    public static class Grupos {
        private String carrito = "grupo-carrito";
        private String dlt = "grupo-dlt-carrito";
    }

    @Data
    public static class Concurrencia {
        private int carrito = 3;
    }
}
