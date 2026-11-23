package com.pedidos360.carrito.Config;

import com.pedidos360.carrito.DTO.EventoInteraccionDTO;
import com.pedidos360.carrito.DTO.ResultadoPagoDTO;
import com.pedidos360.carrito.Exception.PayloadInvalidoException;
import com.pedidos360.carrito.Exception.TransicionInvalidaException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuración explícita del cliente Kafka (criterio 1 de la pauta): fábricas de productor y
 * consumidor, un container factory por tipo de DTO y el manejo de errores con DLT.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    private static final String SERVICIO = "ms-carrito";

    private final KafkaTopicosProperties topicos;
    private final String bootstrapServers;

    public KafkaConfig(KafkaTopicosProperties topicos,
                       @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.topicos = topicos;
        this.bootstrapServers = bootstrapServers;
    }

    // =====================================================================
    // Productores
    // =====================================================================

    private Map<String, Object> propiedadesProductor(String clientId) {
        Map<String, Object> p = new HashMap<>();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // acks=all + idempotencia: el evento queda en todas las réplicas ISR y un reintento de red
        // no lo duplica (con min.insync.replicas=2 se tolera la caída de un broker).
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.RETRIES_CONFIG, 10);
        // 5 es el máximo que permite la idempotencia conservando el orden por partición.
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        // Tope total por mensaje (incluye reintentos): pasado esto el callback informa el error.
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        // Espera 10 ms para agrupar eventos en lotes: más throughput a costa de latencia imperceptible.
        p.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        // lz4: compresión barata en CPU, buena para JSON repetitivo (clickstream).
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        // No está en §3.1: si el clúster está caído, send() bloquea hasta max.block.ms esperando
        // metadatos (60 s por defecto) y eso colgaría la petición HTTP del carrito. Con 3 s el
        // fallo se loguea rápido y la operación de negocio sigue (dominios de falla independientes).
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        return p;
    }

    @Bean
    public ProducerFactory<String, EventoInteraccionDTO> productorInteraccionesFactory() {
        Map<String, Object> p = propiedadesProductor(SERVICIO + "-producer");
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Sin header __TypeId__: los consumidores deserializan a su propio DTO y no dependen del
        // nombre de la clase Java de este servicio.
        p.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(p);
    }

    @Bean
    public KafkaTemplate<String, EventoInteraccionDTO> kafkaTemplateInteracciones(
            ProducerFactory<String, EventoInteraccionDTO> productorInteraccionesFactory) {
        return new KafkaTemplate<>(productorInteraccionesFactory);
    }

    /**
     * Productor exclusivo del DLT. Un mensaje que no se pudo deserializar llega como byte[]
     * crudo y uno que no pasó la validación llega como DTO: el serializer por tipo publica
     * cada uno tal cual, sin perder el payload original para el análisis posterior.
     */
    @Bean
    public ProducerFactory<Object, Object> productorDltFactory() {
        Map<String, Object> p = propiedadesProductor(SERVICIO + "-dlt-producer");
        Map<Class<?>, Serializer<?>> porTipo = new LinkedHashMap<>();
        porTipo.put(byte[].class, new ByteArraySerializer());
        porTipo.put(String.class, new StringSerializer());
        porTipo.put(Object.class, new JsonSerializer<>().noTypeInfo());
        return new DefaultKafkaProducerFactory<>(p,
                new DelegatingByTypeSerializer(Map.of(String.class, new StringSerializer(),
                        byte[].class, new ByteArraySerializer())),
                new DelegatingByTypeSerializer(porTipo, true));
    }

    @Bean
    public KafkaTemplate<Object, Object> kafkaTemplateDlt(
            @Qualifier("productorDltFactory") ProducerFactory<Object, Object> productorDltFactory) {
        return new KafkaTemplate<>(productorDltFactory);
    }

    // =====================================================================
    // Consumidores
    // =====================================================================

    private Map<String, Object> propiedadesConsumidor(String clientId) {
        Map<String, Object> p = new HashMap<>();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // El offset lo confirma el contenedor después de procesar (AckMode.RECORD): si el servicio
        // cae a mitad de un mensaje, se vuelve a leer en vez de perderse.
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // earliest (§2.2): un grupo nuevo no debe saltarse pagos ya publicados.
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        // Margen amplio: con reintentos (backoff hasta 10 s) un lote no debe provocar un rebalanceo.
        p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45000);
        // Un tercio del session timeout: 3 latidos perdidos antes de declarar muerto al consumidor.
        p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 15000);
        // Sólo lee mensajes de transacciones confirmadas (nunca los de una transacción abortada).
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        return p;
    }

    @Bean
    public ConsumerFactory<String, ResultadoPagoDTO> consumidorResultadosPagoFactory() {
        Map<String, Object> p = propiedadesConsumidor(SERVICIO + "-consumer");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // ErrorHandlingDeserializer: un JSON roto no hace caer el poll en bucle (poison pill);
        // se entrega como DeserializationException al error handler, que lo manda al DLT.
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        p.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        p.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ResultadoPagoDTO.class.getName());
        // El productor (ms-pago) no manda headers de tipo y aunque los mandara apuntarían a SU paquete.
        p.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        // Nunca "*": sólo se permite instanciar clases del paquete DTO propio.
        p.put(JsonDeserializer.TRUSTED_PACKAGES, ResultadoPagoDTO.class.getPackageName());
        return new DefaultKafkaConsumerFactory<>(p);
    }

    /**
     * DefaultErrorHandler (criterio 6): reintentos exponenciales para fallos transitorios (BD,
     * ms-productos caído al liberar stock) y DLT inmediato para lo que reintentar no arregla.
     */
    @Bean
    public DefaultErrorHandler manejadorErroresKafka(
            @Qualifier("kafkaTemplateDlt") KafkaTemplate<Object, Object> kafkaTemplateDlt) {
        // Misma partición que el original: el DLT se crea con las mismas particiones (§2.1) y así
        // se conserva el orden por key también entre los mensajes muertos.
        DeadLetterPublishingRecoverer recuperador = new DeadLetterPublishingRecoverer(kafkaTemplateDlt,
                (registro, ex) -> new TopicPartition(registro.topic() + topicos.getSufijoDlt(), registro.partition()));

        // 3 reintentos: 1 s, 2 s, 4 s (tope 10 s). Suficiente para un corte breve de MySQL o de
        // ms-productos sin bloquear la partición demasiado tiempo.
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10000L);

        DefaultErrorHandler manejador = new DefaultErrorHandler(recuperador, backOff);
        // Reintentar un JSON roto o un payload inválido produce siempre el mismo error.
        manejador.addNotRetryableExceptions(
                DeserializationException.class,
                MethodArgumentNotValidException.class,
                ConstraintViolationException.class,
                PayloadInvalidoException.class,
                TransicionInvalidaException.class);
        manejador.setRetryListeners(new RetryListener() {
            // Se invoca en CADA fallo (también en el primero de una excepción no reintentable, que
            // va directo al DLT): por eso se loguea como "fallo N", no como "reintento N".
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> registro, Exception ex, int intento) {
                log.warn("Fallo {} procesando tópico={} partición={} offset={} key={}: {}",
                        intento, registro.topic(), registro.partition(), registro.offset(), registro.key(),
                        causaRaiz(ex).getMessage());
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> registro, Exception ex) {
                log.error("Mensaje enviado a {}{}: partición={} offset={} key={} causa={}",
                        registro.topic(), topicos.getSufijoDlt(), registro.partition(), registro.offset(),
                        registro.key(), causaRaiz(ex).getClass().getSimpleName());
            }
        });
        return manejador;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ResultadoPagoDTO> resultadoPagoListenerFactory(
            ConsumerFactory<String, ResultadoPagoDTO> consumidorResultadosPagoFactory,
            DefaultErrorHandler manejadorErroresKafka) {
        ConcurrentKafkaListenerContainerFactory<String, ResultadoPagoDTO> f =
                new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(consumidorResultadosPagoFactory);
        // 3 hilos = 3 particiones de pagos-resultado (§2.2): reparto real dentro de grupo-carrito.
        f.setConcurrency(topicos.getConcurrencia().getCarrito());
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        f.setCommonErrorHandler(manejadorErroresKafka);
        f.setAutoStartup(topicos.isAutoArranqueListeners());
        return f;
    }

    // =====================================================================
    // DLT: sólo se registra; el payload se lee como texto porque puede ser JSON inválido.
    // =====================================================================

    @Bean
    public ConsumerFactory<String, String> consumidorDltFactory() {
        Map<String, Object> p = propiedadesConsumidor(SERVICIO + "-dlt-consumer");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(p);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> dltListenerFactory(
            ConsumerFactory<String, String> consumidorDltFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(consumidorDltFactory);
        f.setConcurrency(1);
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Si hasta el registro del DLT falla, se reintenta 2 veces y se descarta: no hay DLT del DLT.
        f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 2L)));
        f.setAutoStartup(topicos.isAutoArranqueListeners());
        return f;
    }

    /** El listener envuelve la excepción real en ListenerExecutionFailedException: se loguea la causa. */
    static Throwable causaRaiz(Throwable ex) {
        Throwable actual = ex;
        while (actual.getCause() != null && actual.getCause() != actual) {
            actual = actual.getCause();
        }
        return actual;
    }
}
