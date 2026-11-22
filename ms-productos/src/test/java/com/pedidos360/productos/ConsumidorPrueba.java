package com.pedidos360.productos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Consumidor crudo para las pruebas. Lee el valor como String a propósito: así la prueba
 * también demuestra que el JSON se puede leer sin conocer las clases Java del productor.
 */
public final class ConsumidorPrueba implements AutoCloseable {

    private final KafkaConsumer<String, String> consumidor;
    private final List<ConsumerRecord<String, String>> leidos = new ArrayList<>();

    private ConsumidorPrueba(String brokers, String topico, boolean desdeInicio) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumidor = new KafkaConsumer<>(p);
        List<TopicPartition> particiones = consumidor.partitionsFor(topico, Duration.ofSeconds(10)).stream()
                .map(i -> new TopicPartition(topico, i.partition())).toList();
        consumidor.assign(particiones);
        if (desdeInicio) {
            consumidor.seekToBeginning(particiones);
        } else {
            consumidor.seekToEnd(particiones);
        }
        // position() fuerza a resolver el seek AHORA; si no, se resolvería en el primer poll,
        // después de que la prueba ya publicó, y se perderían los mensajes.
        particiones.forEach(consumidor::position);
    }

    public static ConsumidorPrueba desdeAhora(String brokers, String topico) {
        return new ConsumidorPrueba(brokers, topico, false);
    }

    public static ConsumidorPrueba desdeInicio(String brokers, String topico) {
        return new ConsumidorPrueba(brokers, topico, true);
    }

    /** Lee hasta que llegue un registro que cumpla la condición; falla si no llega a tiempo. */
    public ConsumerRecord<String, String> esperar(Predicate<ConsumerRecord<String, String>> condicion, Duration maximo) {
        long limite = System.nanoTime() + maximo.toNanos();
        while (System.nanoTime() < limite) {
            for (ConsumerRecord<String, String> r : consumidor.poll(Duration.ofMillis(200))) {
                leidos.add(r);
            }
            for (ConsumerRecord<String, String> r : leidos) {
                if (condicion.test(r)) {
                    return r;
                }
            }
        }
        throw new AssertionError("No llegó el mensaje esperado en " + maximo + "; leídos: " + leidos.size());
    }

    /** Lee durante un tiempo fijo (para afirmar que algo NO se publicó). */
    public List<ConsumerRecord<String, String>> leerDurante(Duration duracion) {
        long limite = System.nanoTime() + duracion.toNanos();
        while (System.nanoTime() < limite) {
            for (ConsumerRecord<String, String> r : consumidor.poll(Duration.ofMillis(200))) {
                leidos.add(r);
            }
        }
        return List.copyOf(leidos);
    }

    public List<ConsumerRecord<String, String>> leidos() {
        return List.copyOf(leidos);
    }

    @Override
    public void close() {
        consumidor.close(Duration.ofSeconds(2));
    }
}
