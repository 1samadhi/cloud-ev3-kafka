package com.kafka.pago.Component;

import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.kafka.pago.DTO.IntentoPagoDTO;
import com.kafka.pago.Exception.PayloadInvalidoException;
import com.kafka.pago.Service.HuellaTarjeta;
import com.kafka.pago.Service.ServicioAntifraude;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Consumidor de payment-attempts (grupo-antifraude, 3 hilos).
 *
 * Sólo hace mensajería: loguea el contexto Kafka, valida el DTO y delega en
 * ServicioAntifraude. Cero reglas de negocio acá (§3.3). Si la validación
 * falla lanza PayloadInvalidoException, que el DefaultErrorHandler manda al
 * DLT sin reintentar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumidorIntentosPago {

    private final ServicioAntifraude servicioAntifraude;
    private final Validator validador;

    @KafkaListener(
            id = "consumidor-antifraude",
            topics = "#{@kafkaTopicosProperties.topicos.intentosPago}",
            groupId = "#{@kafkaTopicosProperties.grupos.antifraude}",
            containerFactory = "fabricaIntentosPago",
            concurrency = "#{@kafkaTopicosProperties.concurrencia.antifraude}")
    public void consumir(ConsumerRecord<String, IntentoPagoDTO> registro) {
        log.info("Intento recibido topico={} particion={} offset={} key={}",
                registro.topic(), registro.partition(), registro.offset(), HuellaTarjeta.abreviar(registro.key()));

        IntentoPagoDTO intento = registro.value();
        if (intento == null) {
            throw new PayloadInvalidoException("Mensaje sin cuerpo en offset " + registro.offset());
        }
        Set<ConstraintViolation<IntentoPagoDTO>> violaciones = validador.validate(intento);
        if (!violaciones.isEmpty()) {
            String detalle = violaciones.stream().map(ConstraintViolation::getMessage).sorted()
                    .collect(Collectors.joining("; "));
            throw new PayloadInvalidoException("IntentoPagoDTO inválido: " + detalle);
        }
        if (registro.key() != null && !registro.key().equals(intento.getHuellaTarjeta())) {
            // No se rechaza (el conteo usa la huella del cuerpo), pero una key
            // distinta rompe la garantía de orden por tarjeta y hay que saberlo.
            log.warn("Key del mensaje distinta de huellaTarjeta en offset {}: el orden por tarjeta no está garantizado",
                    registro.offset());
        }
        servicioAntifraude.procesarIntento(intento);
    }
}
