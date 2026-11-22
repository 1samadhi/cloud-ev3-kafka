package com.pedidos360.productos.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.pedidos360.productos.Config.KafkaTopicosProperties;
import com.pedidos360.productos.DTO.EventoInteraccionDTO;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Productor de {@code interacciones-user} (§3.2). Key = usuarioId: todos los eventos de un
 * usuario van a la misma partición y el consumidor de recomendaciones los ve en orden.
 *
 * Un fallo de Kafka NUNCA rompe la operación de negocio (ver el detalle de un producto):
 * se loguea y se sigue. Por eso este método no lanza excepciones.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductorInteracciones {

    private final KafkaTemplate<String, EventoInteraccionDTO> plantillaInteracciones;
    private final KafkaTopicosProperties propiedades;
    private final Validator validador;

    public CompletableFuture<SendResult<String, EventoInteraccionDTO>> publicar(EventoInteraccionDTO evento) {
        // Validar ANTES de enviar: un evento mal formado terminaría en el DLT del consumidor;
        // es más barato y claro cortarlo aquí, donde se sabe quién lo generó.
        Set<ConstraintViolation<EventoInteraccionDTO>> violaciones = validador.validate(evento);
        if (!violaciones.isEmpty()) {
            String detalle = violaciones.stream().map(ConstraintViolation::getMessage).sorted().collect(Collectors.joining("; "));
            log.error("Evento de interacción inválido, NO se publica: idEvento={} errores=[{}]", evento.getIdEvento(), detalle);
            return CompletableFuture.failedFuture(new IllegalArgumentException(detalle));
        }

        String topico = propiedades.getTopicos().getInteracciones();
        String key = evento.getUsuarioId();
        try {
            return plantillaInteracciones.send(topico, key, evento).whenComplete((resultado, error) -> {
                if (error == null) {
                    var meta = resultado.getRecordMetadata();
                    log.info("Interacción {} publicada: topico={} particion={} offset={} key={} productoId={} idEvento={}",
                            evento.getTipoEvento(), meta.topic(), meta.partition(), meta.offset(), key,
                            evento.getProductoId(), evento.getIdEvento());
                } else {
                    log.error("No se pudo publicar la interacción {}: topico={} key={} productoId={} idEvento={} causa={}",
                            evento.getTipoEvento(), topico, key, evento.getProductoId(), evento.getIdEvento(), error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            // send() puede fallar sincrónicamente (serialización, productor cerrado): se trata igual que un fallo asíncrono.
            log.error("Fallo inmediato al publicar la interacción: topico={} key={} idEvento={} causa={}",
                    topico, key, evento.getIdEvento(), e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }
}
