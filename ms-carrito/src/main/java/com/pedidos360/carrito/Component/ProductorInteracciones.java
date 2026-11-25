package com.pedidos360.carrito.Component;

import com.pedidos360.carrito.Config.KafkaTopicosProperties;
import com.pedidos360.carrito.DTO.EventoInteraccionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Productor de interacciones-user (criterio 4). La key es usuarioId: todos los eventos de un
 * usuario caen en la misma partición y ms-recomendaciones los procesa en orden.
 *
 * Nunca lanza: Kafka es el log analítico, no parte de la transacción de negocio. Si el clúster
 * está caído, el carrito sigue funcionando y el error queda en el log.
 */
@Slf4j
@Component
public class ProductorInteracciones {

    private final KafkaTemplate<String, EventoInteraccionDTO> kafkaTemplate;
    private final KafkaTopicosProperties topicos;

    public ProductorInteracciones(
            @Qualifier("kafkaTemplateInteracciones") KafkaTemplate<String, EventoInteraccionDTO> kafkaTemplate,
            KafkaTopicosProperties topicos) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicos = topicos;
    }

    public void publicar(EventoInteraccionDTO evento) {
        String topico = topicos.getTopicos().getInteracciones();
        String key = evento.getUsuarioId();
        try {
            kafkaTemplate.send(topico, key, evento).whenComplete((resultado, error) -> {
                if (error == null) {
                    var meta = resultado.getRecordMetadata();
                    log.info("Evento {} publicado: tópico={} partición={} offset={} key={} idEvento={}",
                            evento.getTipoEvento(), meta.topic(), meta.partition(), meta.offset(), key,
                            evento.getIdEvento());
                } else {
                    log.error("No se pudo publicar el evento {} en tópico={} key={} idEvento={}: {}. "
                                    + "La operación de negocio continúa.",
                            evento.getTipoEvento(), topico, key, evento.getIdEvento(), error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            // send() puede fallar de forma síncrona (sin metadatos tras max.block.ms, serialización).
            log.error("Fallo síncrono publicando el evento {} en tópico={} key={} idEvento={}: {}. "
                            + "La operación de negocio continúa.",
                    evento.getTipoEvento(), topico, key, evento.getIdEvento(), e.getMessage());
        }
    }
}
