package com.kafka.recomendaciones.Component;

import com.kafka.recomendaciones.Config.KafkaTopicosProperties;
import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Productor de interacciones-user (§3.2). Key = usuarioId: todos los eventos de
 * un usuario caen en la misma particion y el consumidor los ve en orden, que es
 * lo que el perfil (con decaimiento temporal) necesita.
 *
 * Solo publica, nunca persiste: el unico que guarda es el consumidor de
 * grupo-recomendaciones. Si Kafka falla se loguea y la operacion HTTP sigue
 * (no lanza): una interaccion perdida no justifica romper la pantalla.
 */
@Slf4j
@Component
public class ProductorInteracciones {

    private final KafkaTemplate<String, EventoInteraccionDTO> plantillaInteracciones;
    private final KafkaTopicosProperties props;

    public ProductorInteracciones(KafkaTemplate<String, EventoInteraccionDTO> plantillaInteracciones,
                                  KafkaTopicosProperties props) {
        this.plantillaInteracciones = plantillaInteracciones;
        this.props = props;
    }

    public CompletableFuture<SendResult<String, EventoInteraccionDTO>> publicar(EventoInteraccionDTO evento) {
        String topico = props.getTopicos().getInteracciones();
        String key = evento.getUsuarioId();
        try {
            return plantillaInteracciones.send(topico, key, evento).whenComplete((resultado, error) -> {
                if (error == null) {
                    var meta = resultado.getRecordMetadata();
                    log.info("Interaccion publicada topico={} particion={} offset={} key={} tipo={} producto={}",
                            meta.topic(), meta.partition(), meta.offset(), key, evento.getTipoEvento(),
                            evento.getProductoId());
                } else {
                    log.error("Fallo al publicar interaccion topico={} key={} tipo={} idEvento={}: {}",
                            topico, key, evento.getTipoEvento(), evento.getIdEvento(), error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            // send() puede lanzar en el acto (sin metadatos tras max.block.ms=5 s).
            log.error("Fallo al publicar interaccion topico={} key={} idEvento={}: {}",
                    topico, key, evento.getIdEvento(), e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }
}
