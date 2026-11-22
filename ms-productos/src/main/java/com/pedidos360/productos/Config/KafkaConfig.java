package com.pedidos360.productos.Config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import com.pedidos360.productos.DTO.EventoInteraccionDTO;
import com.pedidos360.productos.DTO.ProductoCatalogoDTO;

import lombok.RequiredArgsConstructor;

/**
 * Configuración EXPLÍCITA del cliente Kafka (§3.1).
 *
 * ms-productos sólo produce (VER_PRODUCTO y snapshots de catálogo), así que aquí hay
 * productores y no consumidores: declarar un ConsumerFactory sin ningún listener sería
 * configuración muerta. Hay una fábrica y una plantilla POR TIPO DE DTO para que cada
 * {@code KafkaTemplate<String, DTO>} quede tipado y el compilador impida publicar un DTO
 * en el tópico equivocado.
 */
@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    private static final String SERVICIO = "ms-productos";

    private final KafkaTopicosProperties propiedades;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Propiedades comunes de ambos productores.
     *
     * @param sufijo distingue el client.id de cada productor: dos productores con el mismo
     *               client.id chocan al registrar sus MBeans JMX y sus métricas se mezclan.
     */
    Map<String, Object> configuracionProductor(String sufijo) {
        Map<String, Object> c = new HashMap<>();
        c.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // acks=all + min.insync.replicas=2 del tópico: un evento confirmado sobrevive a la
        // caída de un broker.
        c.put(ProducerConfig.ACKS_CONFIG, "all");
        // Idempotencia: los reintentos no duplican mensajes ni los desordenan por partición.
        c.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        c.put(ProducerConfig.RETRIES_CONFIG, 10);
        // 5 es el máximo compatible con idempotencia conservando el orden por partición.
        c.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        // Tope total de un envío (incluye reintentos): pasado esto se loguea el error y se
        // sigue; el negocio nunca espera indefinidamente a Kafka.
        c.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        c.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        // Tiempo que send() puede bloquear al hilo que llama (p. ej. el hilo HTTP del detalle
        // de producto) esperando metadatos; el default de Kafka (60 s) colgaría la tienda si
        // el clúster está caído. No está en la lista de §3.1: se agrega por esa razón.
        c.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, propiedades.getMaxBloqueoMs());
        // 10 ms de espera agrupan eventos en lotes sin que el usuario lo note.
        c.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        c.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        // lz4: el mismo códec que el tópico interacciones-user; JSON repetitivo comprime bien.
        c.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        c.put(ProducerConfig.CLIENT_ID_CONFIG, SERVICIO + "-producer-" + sufijo);
        c.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        c.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Sin headers de tipo: el consumidor deserializa a SU propio DTO y no depende del
        // paquete Java de este servicio (no hay librería compartida, §2.3).
        c.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return c;
    }

    @Bean
    public ProducerFactory<String, EventoInteraccionDTO> fabricaProductorInteracciones() {
        return new DefaultKafkaProducerFactory<>(configuracionProductor("interacciones"));
    }

    @Bean
    public KafkaTemplate<String, EventoInteraccionDTO> plantillaInteracciones() {
        return new KafkaTemplate<>(fabricaProductorInteracciones());
    }

    @Bean
    public ProducerFactory<String, ProductoCatalogoDTO> fabricaProductorCatalogo() {
        return new DefaultKafkaProducerFactory<>(configuracionProductor("catalogo"));
    }

    @Bean
    public KafkaTemplate<String, ProductoCatalogoDTO> plantillaCatalogo() {
        return new KafkaTemplate<>(fabricaProductorCatalogo());
    }
}
