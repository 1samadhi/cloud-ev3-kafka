package com.kafka.recomendaciones.Component;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.kafka.recomendaciones.DTO.InteractionRequestsDTO;
import com.kafka.recomendaciones.Model.Interacciones;
import com.kafka.recomendaciones.Repository.InteraccionesRepository;
import com.kafka.recomendaciones.Service.RecomendacionesService;

@Component
public class InteraccionComponent {
    private final InteraccionesRepository interaccionesRepository;
    private final RecomendacionesService recomendacionesService;

    public InteraccionComponent(InteraccionesRepository interaccionesRepository, 
                                RecomendacionesService recomendacionesService) {
        this.interaccionesRepository = interaccionesRepository;
        this.recomendacionesService = recomendacionesService;
    }

    @KafkaListener(topics = "interacciones-user", groupId = "recommendation-consumer-group")
    public void consumirInteraccion(InteractionRequestsDTO dto) {
        try {
            if (dto == null) {
                System.err.println("El DTO recibido es nulo.");
                return;
            }

            System.out.println("Procesando evento de Kafka para usuario: " + dto.getClientId() + " | Item: " + dto.getItemId());

            // 1. Calcular el peso según la acción (o reutilizar la lógica de negocio)
            int weight = switch (dto.getAction().toLowerCase()) {
                case "purchase" -> 3;
                case "cart"     -> 2;
                default         -> 1;
            };

            // 2. Mapear del DTO a la Entidad JPA de Base de Datos
            Interacciones eventoSql = new Interacciones(
                    dto.getClientId(),
                    dto.getItemId(),
                    dto.getAction(),
                    weight,
                    dto.getCategory(),
                    dto.getProductType()
            );

            // 3. Guardar en la base de datos relacional
            interaccionesRepository.save(eventoSql);

            // 4. Actualizar recomendaciones
            recomendacionesService.calcularYGuardarTop5(dto.getClientId());

            System.out.println("Interacción procesada, guardada y recomendaciones actualizadas para: " + dto.getClientId());

        } catch (Exception e) {
            System.err.println("Error al procesar el mensaje del consumidor de Kafka: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
