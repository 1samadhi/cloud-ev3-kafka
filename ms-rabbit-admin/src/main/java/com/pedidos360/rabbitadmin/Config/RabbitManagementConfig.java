package com.pedidos360.rabbitadmin.Config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RestClient dedicado a la API HTTP de management.
 *
 * AmqpAdmin (RabbitAdmin) no se define acá: lo crea la autoconfiguración de
 * Spring Boot a partir de spring.rabbitmq.* (addresses con los 3 nodos), y la
 * conexión AMQP es perezosa: el servicio arranca aunque el clúster no esté.
 */
@Configuration
@EnableConfigurationProperties({RabbitManagementProperties.class, ProteccionRecursosProperties.class})
public class RabbitManagementConfig {

    @Bean
    public RestClient restClientManagement(RestClient.Builder builder, RabbitManagementProperties props) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(props.getTimeoutConexion());
        fabrica.setReadTimeout(props.getTimeoutLectura());
        return configurarAutenticacion(builder.requestFactory(fabrica), props).build();
    }

    /**
     * Basic auth y Accept JSON. Está separado del bean para que los tests lo
     * apliquen sobre un builder enlazado a MockRestServiceServer y verifiquen
     * que de verdad se manda la cabecera Authorization (la API responde 401 sin
     * ella). No toca la request factory: si lo hiciera, pisaría la del mock.
     */
    public static RestClient.Builder configurarAutenticacion(RestClient.Builder builder,
                                                             RabbitManagementProperties props) {
        return builder.defaultHeaders(h -> {
            h.setBasicAuth(props.getUsuario(), props.getClave(), StandardCharsets.UTF_8);
            h.setAccept(List.of(MediaType.APPLICATION_JSON));
        });
    }
}
