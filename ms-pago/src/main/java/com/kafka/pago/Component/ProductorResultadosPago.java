package com.kafka.pago.Component;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.kafka.pago.Config.KafkaTopicosProperties;
import com.kafka.pago.DTO.ResultadoPagoDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Productor de pagos-resultado (key = idOrden: todos los eventos de un pedido
 * van ordenados a la misma partición).
 *
 * No bloquea ni lanza: cuando se publica, el cobro ya ocurrió en la pasarela,
 * y un fallo de Kafka no puede deshacerlo ni romper el redirect del cliente.
 * Se loguea ERROR con todo el contexto para reconciliar a mano.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductorResultadosPago {

    private final KafkaTemplate<String, ResultadoPagoDTO> plantillaResultadosPago;
    private final KafkaTopicosProperties propiedades;

    public void publicar(ResultadoPagoDTO resultado) {
        String topico = propiedades.getTopicos().getResultadosPago();
        String key = resultado.getIdOrden();
        try {
            plantillaResultadosPago.send(topico, key, resultado).whenComplete((r, error) -> {
                if (error == null) {
                    var meta = r.getRecordMetadata();
                    log.info("Resultado publicado topico={} particion={} offset={} key={} estado={} pasarela={}",
                            meta.topic(), meta.partition(), meta.offset(), key, resultado.getEstado(),
                            resultado.getPasarela());
                } else {
                    log.error("Fallo al publicar resultado topico={} key={} estado={} cliente={} monto={}: {}",
                            topico, key, resultado.getEstado(), resultado.getIdCliente(), resultado.getMonto(),
                            error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.error("Fallo al publicar resultado topico={} key={} estado={}: {}",
                    topico, key, resultado.getEstado(), e.getMessage());
        }
    }
}
