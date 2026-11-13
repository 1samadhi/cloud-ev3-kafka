package com.kafka.recomendaciones.Service;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.kafka.recomendaciones.DTO.InteractionRequestsDTO;
import com.kafka.recomendaciones.Model.Interacciones;
import com.kafka.recomendaciones.Repository.InteraccionesRepository;

import lombok.extern.slf4j.Slf4j;


@Service
@Slf4j
public class InteraccionService {
    private final KafkaTemplate<String, InteractionRequestsDTO> kafkaTemplate;
    private final InteraccionesRepository interaccionesRepository;

    public InteraccionService(InteraccionesRepository interaccionesRepository,
                              KafkaTemplate<String, InteractionRequestsDTO> kafkaTemplate) {
        this.interaccionesRepository = interaccionesRepository;
        this.kafkaTemplate = kafkaTemplate;
    }
    
    private static final String TOPIC_RECOMENDACION = "interacciones-user";

    public void interaccionEvento(String idCliente,  InteractionRequestsDTO request) {
        // CORRECCION A LA GUIA: aca la guia hacia interaccionesRepository.save(evento)
        // ADEMAS de publicar a Kafka, y el consumidor volvia a guardar el mismo evento.
        // Resultado: cada interaccion quedaba DUPLICADA en la tabla y todos los puntajes
        // valian el doble. El productor solo publica; el consumidor es el unico que
        // persiste, asi el evento de Kafka es la fuente de verdad.
        //
        // El peso se calcula en el consumidor, que es donde se usa.
        kafkaTemplate.send(TOPIC_RECOMENDACION, idCliente, request);
        log.info("Interaccion publicada en Kafka para el cliente {} (item {}, accion {})",
                idCliente, request.getItemId(), request.getAction());
    }
}
