package com.pedidos360.carrito.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** Cliente HTTP hacia ms-productos (comandos transaccionales: precio, reserva y liberación de stock). */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient productosRestClient(RestClient.Builder builder,
                                          @Value("${app.productos.url:http://localhost:8086}") String url,
                                          @Value("${app.productos.timeout-conexion-ms:2000}") long timeoutConexion,
                                          @Value("${app.productos.timeout-lectura-ms:5000}") long timeoutLectura) {
        // Timeouts explícitos: sin ellos un ms-productos colgado dejaría colgado al carrito
        // (y a los hilos del consumidor Kafka) indefinidamente.
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofMillis(timeoutConexion));
        fabrica.setReadTimeout(Duration.ofMillis(timeoutLectura));
        return builder.baseUrl(url).requestFactory(fabrica).build();
    }
}
