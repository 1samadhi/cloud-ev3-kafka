package com.kafka.pago.Config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonLoggingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.kafka.pago.DTO.IntentoPagoDTO;
import com.kafka.pago.DTO.ResultadoPagoDTO;
import com.kafka.pago.Exception.PayloadInvalidoException;
import com.kafka.pago.Service.HuellaTarjeta;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Configuración EXPLÍCITA del cliente Kafka de ms-pago (contrato §3.1/§3.4).
 *
 * Se declaran a mano las fábricas en lugar de dejarlas a spring.kafka.* para
 * que cada propiedad quede a la vista con su motivo, y porque el servicio
 * produce dos DTO distintos y consume uno (más el DLT): con la autoconfiguración
 * habría un único serializador/deserializador para todo.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    /** Paquete de los DTO: el único en el que el JsonDeserializer confía. */
    private static final String PAQUETE_DTO = "com.kafka.pago.DTO";

    private final String bootstrapServers;
    private final KafkaTopicosProperties propiedades;

    public KafkaConfig(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                       KafkaTopicosProperties propiedades) {
        this.bootstrapServers = bootstrapServers;
        this.propiedades = propiedades;
    }

    // ------------------------------------------------------------------ productores

    /** Propiedades comunes a todos los productores del servicio. */
    private Map<String, Object> propiedadesProductor() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // acks=all + min.insync.replicas=2 del tópico: un intento de pago sólo se
        // da por publicado cuando dos réplicas lo tienen; la caída de un broker
        // no pierde la evidencia que el antifraude necesita.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // Idempotencia: los reintentos del productor no duplican el mensaje
        // (un intento duplicado inflaría el conteo de la ventana).
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 10);
        // 5 es el máximo que la idempotencia admite sin perder el orden por partición.
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        // Tope total por mensaje (incluye reintentos); debe ser >= linger + request.timeout.
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        // Sin esto, send() se queda hasta 60 s bloqueado esperando metadata si el
        // clúster está caído, y el HTTP no podría responder 503 a los 5 s.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        // 10 ms de espera para armar lotes: imperceptible en un checkout y
        // agrupa las ráfagas (justo las que interesan al antifraude).
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        // Spring agrega "-1", "-2"... por fábrica: cada productor queda identificable en el broker.
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "ms-pago-producer");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Sin el header __TypeId__: el consumidor (ms-carrito, otro paquete Java)
        // decide el tipo por su configuración y no depende de nuestras clases.
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return props;
    }

    @Bean
    public ProducerFactory<String, IntentoPagoDTO> productorIntentosPagoFactory() {
        return new DefaultKafkaProducerFactory<>(propiedadesProductor());
    }

    @Bean
    public KafkaTemplate<String, IntentoPagoDTO> plantillaIntentosPago() {
        return new KafkaTemplate<>(productorIntentosPagoFactory());
    }

    @Bean
    public ProducerFactory<String, ResultadoPagoDTO> productorResultadosPagoFactory() {
        return new DefaultKafkaProducerFactory<>(propiedadesProductor());
    }

    @Bean
    public KafkaTemplate<String, ResultadoPagoDTO> plantillaResultadosPago() {
        return new KafkaTemplate<>(productorResultadosPagoFactory());
    }

    /**
     * Plantilla del DeadLetterPublishingRecoverer. Necesita dos serializadores:
     * si el mensaje murió al deserializar, el recoverer reenvía los BYTES
     * originales (no hay objeto); si murió por validación, reenvía el DTO.
     */
    @Bean
    public KafkaTemplate<String, Object> plantillaDlt() {
        Map<Class<?>, Serializer<?>> porTipo = new LinkedHashMap<>();
        porTipo.put(byte[].class, new ByteArraySerializer());
        porTipo.put(Object.class, new JsonSerializer<>().noTypeInfo());
        Map<String, Object> props = propiedadesProductor();
        props.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        // El JsonSerializer de arriba ya va configurado por setter (noTypeInfo).
        // Si además recibe ADD_TYPE_INFO_HEADERS por propiedades lanza
        // IllegalStateException ("setters or configuration properties; not both"):
        // la publicación al DLT fallaba siempre y el DefaultErrorHandler volvía a
        // buscar el mismo registro sin fin, trabando la partición. Lo detectó la
        // prueba con @EmbeddedKafka.
        props.remove(JsonSerializer.ADD_TYPE_INFO_HEADERS);
        DefaultKafkaProducerFactory<String, Object> fabrica = new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), new DelegatingByTypeSerializer(porTipo, true));
        return new KafkaTemplate<>(fabrica);
    }

    // ------------------------------------------------------------------ consumidores

    /** Propiedades comunes a los consumidores; el deserializador de valor lo pone cada fábrica. */
    private Map<String, Object> propiedadesConsumidor() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // El offset lo confirma el contenedor después de procesar (AckMode.RECORD):
        // con auto-commit un intento podría quedar confirmado sin haberse contado.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // earliest (§2.2): un grupo nuevo no debe saltarse intentos ya publicados.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45000);
        // Un tercio de la sesión: el coordinador detecta un consumidor muerto sin falsos positivos.
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 15000);
        // No leer mensajes de transacciones abortadas si algún productor llega a usarlas.
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "ms-pago-consumer");
        return props;
    }

    @Bean
    public ConsumerFactory<String, IntentoPagoDTO> consumidorIntentosPagoFactory() {
        Map<String, Object> props = propiedadesConsumidor();
        // ErrorHandlingDeserializer: un JSON roto ya no revienta el poll en bucle
        // (poison pill); el error llega al DefaultErrorHandler, que lo manda al DLT.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, IntentoPagoDTO.class.getName());
        // El tipo lo decide esta configuración, no un header que manda el productor.
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        // Nunca "*": confiar en cualquier paquete permite instanciar clases
        // arbitrarias desde un mensaje (deserialización insegura).
        props.put(JsonDeserializer.TRUSTED_PACKAGES, PAQUETE_DTO);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Manejo de errores del consumidor antifraude (§3.4): reintentos con
     * backoff exponencial para fallas transitorias (la base no responde) y DLT
     * en la MISMA partición cuando se agotan o cuando el error no tiene arreglo.
     */
    @Bean
    public DefaultErrorHandler manejadorErroresIntentosPago(KafkaTemplate<String, Object> plantillaDlt) {
        DeadLetterPublishingRecoverer recuperador = new DeadLetterPublishingRecoverer(plantillaDlt,
                (registro, ex) -> new TopicPartition(registro.topic() + propiedades.getTopicos().getSufijoDlt(),
                        registro.partition()));

        KafkaTopicosProperties.Reintentos r = propiedades.getReintentos();
        ExponentialBackOff backOff = new ExponentialBackOffWithMaxRetries(r.getMaximo());
        backOff.setInitialInterval(r.getIntervaloInicialMs());
        backOff.setMultiplier(r.getMultiplicador());
        backOff.setMaxInterval(r.getIntervaloMaximoMs());

        DefaultErrorHandler manejador = new DefaultErrorHandler(recuperador, backOff);
        // Reintentar un JSON roto o un DTO inválido no lo arregla: directo al DLT.
        manejador.addNotRetryableExceptions(
                DeserializationException.class,
                MethodArgumentNotValidException.class,
                ConstraintViolationException.class,
                PayloadInvalidoException.class);
        // DataAccessException (y sus transitorias) quedan reintentables: es el
        // caso típico de MySQL reiniciándose y conviene esperar.
        manejador.addRetryableExceptions(DataAccessException.class);
        manejador.setRetryListeners(new RetryListener() {
            // failedDelivery se invoca en CADA fallo, también en el primero de un
            // error no reintentable (que va directo al DLT): por eso dice "fallo",
            // no "reintento", y el destino final se loguea en recovered().
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> registro, Exception ex, int intento) {
                log.warn("Fallo {} (máx. {} reintentos) topico={} particion={} offset={} key={} causa={}",
                        intento, r.getMaximo(), registro.topic(), registro.partition(), registro.offset(),
                        HuellaTarjeta.abreviar(String.valueOf(registro.key())), causaRaiz(ex));
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> registro, Exception ex) {
                log.error("Enviado a {}{} topico={} particion={} offset={} causa={}", registro.topic(),
                        propiedades.getTopicos().getSufijoDlt(), registro.topic(), registro.partition(),
                        registro.offset(), causaRaiz(ex));
            }
        });
        return manejador;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, IntentoPagoDTO> fabricaIntentosPago(
            DefaultErrorHandler manejadorErroresIntentosPago) {
        ConcurrentKafkaListenerContainerFactory<String, IntentoPagoDTO> fabrica =
                new ConcurrentKafkaListenerContainerFactory<>();
        fabrica.setConsumerFactory(consumidorIntentosPagoFactory());
        fabrica.setConcurrency(propiedades.getConcurrencia().getAntifraude());
        fabrica.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        fabrica.setCommonErrorHandler(manejadorErroresIntentosPago);
        return fabrica;
    }

    /**
     * El DLT se lee como texto plano: puede traer un JSON válido que no pasó la
     * validación o bytes que ni siquiera son JSON, y el objetivo es registrarlos
     * tal cual, no volver a interpretarlos.
     */
    @Bean
    public ConsumerFactory<String, String> consumidorDltFactory() {
        Map<String, Object> props = propiedadesConsumidor();
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "ms-pago-consumer-dlt");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> fabricaDlt() {
        ConcurrentKafkaListenerContainerFactory<String, String> fabrica = new ConcurrentKafkaListenerContainerFactory<>();
        fabrica.setConsumerFactory(consumidorDltFactory());
        fabrica.setConcurrency(1);
        fabrica.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Si registrar un mensaje muerto falla sólo se loguea: un DLT del DLT
        // abriría un ciclo sin fin.
        fabrica.setCommonErrorHandler(new CommonLoggingErrorHandler());
        return fabrica;
    }

    /** El contenedor envuelve todo en ListenerExecutionFailedException: interesa el mensaje de la causa. */
    private static String causaRaiz(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
