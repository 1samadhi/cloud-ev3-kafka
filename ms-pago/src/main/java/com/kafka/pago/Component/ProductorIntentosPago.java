package com.kafka.pago.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.kafka.pago.Config.KafkaTopicosProperties;
import com.kafka.pago.DTO.IntentoPagoDTO;
import com.kafka.pago.Exception.KafkaNoDisponibleException;
import com.kafka.pago.Service.HuellaTarjeta;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Productor de payment-attempts. Key = huella de la tarjeta: misma tarjeta,
 * misma partición, orden garantizado para la ventana.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductorIntentosPago {

    private final KafkaTemplate<String, IntentoPagoDTO> plantillaIntentosPago;
    private final KafkaTopicosProperties propiedades;

    public CompletableFuture<SendResult<String, IntentoPagoDTO>> publicar(IntentoPagoDTO intento) {
        String topico = propiedades.getTopicos().getIntentosPago();
        String key = intento.getHuellaTarjeta();
        return plantillaIntentosPago.send(topico, key, intento).whenComplete((resultado, error) -> {
            if (error == null) {
                var meta = resultado.getRecordMetadata();
                log.info("Intento publicado topico={} particion={} offset={} key={} idIntento={} orden={}",
                        meta.topic(), meta.partition(), meta.offset(), HuellaTarjeta.abreviar(key),
                        intento.getIdIntento(), intento.getIdOrden());
            } else {
                log.error("Fallo al publicar intento topico={} key={} idIntento={} orden={} cliente={}: {}",
                        topico, HuellaTarjeta.abreviar(key), intento.getIdIntento(), intento.getIdOrden(),
                        intento.getIdCliente(), error.getMessage());
            }
        });
    }

    /**
     * Excepción a la regla de §3.2: acá publicar SÍ es parte del negocio (es el
     * registro que alimenta el antifraude), así que se espera el ack y, si no
     * llega en el plazo, se corta el pago con 503.
     */
    public SendResult<String, IntentoPagoDTO> publicarConConfirmacion(IntentoPagoDTO intento) {
        int segundos = propiedades.getTimeoutConfirmacionSegundos();
        try {
            return publicar(intento).get(segundos, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaNoDisponibleException("Interrumpido esperando la confirmación de Kafka", e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new KafkaNoDisponibleException("Kafka no confirmó el intento en " + segundos + " s", e);
        }
    }
}
