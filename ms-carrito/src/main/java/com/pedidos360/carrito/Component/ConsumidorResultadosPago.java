package com.pedidos360.carrito.Component;

import com.pedidos360.carrito.DTO.ResultadoPagoDTO;
import com.pedidos360.carrito.Exception.PayloadInvalidoException;
import com.pedidos360.carrito.Service.ResultadoProcesamiento;
import com.pedidos360.carrito.Service.ServicioPedidos;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consumidor de pagos-resultado (criterio 5). Sólo mensajería: loguea tópico/partición/offset/key,
 * valida y delega en {@link ServicioPedidos}. Cero reglas de negocio aquí (§3.3).
 *
 * grupo-carrito con concurrency 3 = 3 particiones de pagos-resultado (§2.2): cada hilo consume una
 * partición y, como la key es idOrden, los resultados de un mismo pedido llegan en orden.
 *
 * Qué pasa con cada fallo (lo decide el DefaultErrorHandler de KafkaConfig):
 *  - JSON roto / enum desconocido → DeserializationException → DLT sin reintentos.
 *  - payload sin idOrden/estado    → PayloadInvalidoException → DLT sin reintentos.
 *  - BD o ms-productos caídos      → 3 reintentos exponenciales → DLT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumidorResultadosPago {

    private final Validator validator;
    private final ServicioPedidos servicioPedidos;

    @KafkaListener(id = "consumidor-resultados-pago",
            topics = "#{@kafkaTopicosProperties.topicos.pagosResultado}",
            groupId = "#{@kafkaTopicosProperties.grupos.carrito}",
            containerFactory = "resultadoPagoListenerFactory",
            concurrency = "#{@kafkaTopicosProperties.concurrencia.carrito}")
    public void recibir(ConsumerRecord<String, ResultadoPagoDTO> registro) {
        ResultadoPagoDTO resultado = registro.value();
        log.info("Resultado de pago recibido: tópico={} partición={} offset={} key={} estado={} pasarela={}",
                registro.topic(), registro.partition(), registro.offset(), registro.key(),
                resultado == null ? null : resultado.getEstado(),
                resultado == null ? null : resultado.getPasarela());
        validar(registro);
        ResultadoProcesamiento efecto = servicioPedidos.procesarResultadoPago(resultado);
        log.info("Resultado de pago procesado: key={} offset={} efecto={}", registro.key(), registro.offset(), efecto);
    }

    void validar(ConsumerRecord<String, ResultadoPagoDTO> registro) {
        ResultadoPagoDTO resultado = registro.value();
        if (resultado == null) {
            // Un tombstone en pagos-resultado no tiene significado: no hay nada que procesar ni reintentar.
            throw new PayloadInvalidoException("Mensaje sin cuerpo en partición=" + registro.partition()
                    + " offset=" + registro.offset());
        }
        Set<ConstraintViolation<ResultadoPagoDTO>> violaciones = validator.validate(resultado);
        if (!violaciones.isEmpty()) {
            String detalle = violaciones.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw new PayloadInvalidoException("ResultadoPagoDTO inválido (key=" + registro.key()
                    + ", offset=" + registro.offset() + "): " + detalle);
        }
    }
}
