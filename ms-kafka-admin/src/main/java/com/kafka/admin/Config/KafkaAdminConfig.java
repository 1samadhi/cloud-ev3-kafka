package com.kafka.admin.Config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * Configuración singleton del AdminClient de Kafka.
 * Lee los brokers de SPRING_KAFKA_BOOTSTRAP_SERVERS o del valor por defecto.
 */
@Configuration
@EnableConfigurationProperties(KafkaAdminProperties.class)
public class KafkaAdminConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean(destroyMethod = "close")
    public AdminClient kafkaAdminClient() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Identifica a este cliente en los logs del broker y en las cuotas.
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "ms-kafka-admin");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        // Tope total por operación (incluye reintentos internos): alineado con
        // app.kafka-admin.timeout-ms para que el AdminClient no siga reintentando
        // algo que la API ya respondió como 503.
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15000);
        return AdminClient.create(props);
    }
}
