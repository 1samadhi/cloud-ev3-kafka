package com.kafka.recomendaciones.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kafka.recomendaciones.DTO.InteractionRequestsDTO;
import com.kafka.recomendaciones.Service.InteraccionService;

@RestController 
@RequestMapping ("/api/interacciones")
// La guia pone @CrossOrigin solo en RecomendacionesController y lo olvida aca.
// Sin esto el navegador bloquea el POST desde el frontend (preflight CORS) y la
// pantalla de recomendaciones puede leer el Top 5 pero no generar interacciones.
@CrossOrigin(origins = "*")
public class InteraccionController {
    private final InteraccionService interaccionService;

    public InteraccionController(InteraccionService interaccionService) {
        this.interaccionService = interaccionService;
    }
    
    @PostMapping
    public ResponseEntity<String> trackInteraction(@RequestBody InteractionRequestsDTO request) {
       interaccionService.interaccionEvento(request.getClientId(), request );
        return ResponseEntity.ok("Evento de interacción enviado a Kafka exitosamente (Peso: " + request.getAction().toLowerCase() + ")");
    }
}
