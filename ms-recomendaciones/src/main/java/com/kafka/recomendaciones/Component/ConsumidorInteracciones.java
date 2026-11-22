package com.kafka.recomendaciones.Component;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.Service.ServicioRecomendaciones;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * grupo-recomendaciones sobre interacciones-user: concurrency 3 sobre 6
 * particiones = cada hilo atiende 2 (REPARTO, criterio 8). Offset earliest: un
 * grupo nuevo reconstruye el historial desde lo que el topico aun retiene.
 *
 * Solo mensajeria: loguea el contexto Kafka, valida y delega. Las excepciones
 * del servicio (DynamoDB caido) suben al contenedor, que reintenta con backoff y
 * termina en interacciones-user.DLT; este grupo es el UNICO que publica en ese DLT.
 */
@Slf4j
@Component
public class ConsumidorInteracciones {

    private final ValidadorMensajes validador;
    private final ServicioRecomendaciones servicio;

    public ConsumidorInteracciones(ValidadorMensajes validador, ServicioRecomendaciones servicio) {
        this.validador = validador;
        this.servicio = servicio;
    }

    @KafkaListener(
            id = "consumidor-recomendaciones",
            topics = "#{@kafkaTopicosProperties.topicos.interacciones}",
            groupId = "#{@kafkaTopicosProperties.grupos.recomendaciones}",
            containerFactory = "fabricaListenerInteracciones",
            concurrency = "#{@kafkaTopicosProperties.concurrencia.recomendaciones}",
            clientIdPrefix = "ms-recomendaciones-recomendaciones")
    public void consumir(ConsumerRecord<String, EventoInteraccionDTO> registro) {
        log.info("Interaccion recibida [grupo-recomendaciones] topico={} particion={} offset={} key={}",
                registro.topic(), registro.partition(), registro.offset(), registro.key());
        EventoInteraccionDTO evento = validador.validar(registro, "EventoInteraccionDTO");
        if (registro.key() != null && !registro.key().equals(evento.getUsuarioId())) {
            // No se rechaza (el servicio usa el usuarioId del cuerpo), pero con otra
            // key el orden por usuario deja de estar garantizado.
            log.warn("Key distinta de usuarioId en offset {}: el orden por usuario no esta garantizado",
                    registro.offset());
        }
        servicio.procesarEvento(evento);
    }
}
