package com.kafka.recomendaciones.Controller;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.InteractionRequestsDTO;
import com.kafka.recomendaciones.DTO.ResultadoSimulacionDTO;
import com.kafka.recomendaciones.Service.ServicioInteracciones;
import com.kafka.recomendaciones.Service.SimuladorInteracciones;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/interacciones con el cuerpo historico de la consola, y el
 * generador de trafico de la demo.
 *
 * Responde 202 Accepted (no 200): el evento queda publicado, pero las
 * recomendaciones se recalculan despues, cuando lo consume grupo-recomendaciones.
 */
@RestController
@RequestMapping("/api/interacciones")
public class InteraccionController {

    private final ServicioInteracciones servicio;
    private final SimuladorInteracciones simulador;

    public InteraccionController(ServicioInteracciones servicio, SimuladorInteracciones simulador) {
        this.servicio = servicio;
        this.simulador = simulador;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> registrar(@Valid @RequestBody InteractionRequestsDTO peticion) {
        EventoInteraccionDTO evento = servicio.registrar(peticion);
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("mensaje", "Interaccion publicada en Kafka; las recomendaciones se recalculan al consumirla");
        cuerpo.put("idEvento", evento.getIdEvento());
        cuerpo.put("usuarioId", evento.getUsuarioId());
        cuerpo.put("tipoEvento", evento.getTipoEvento());
        cuerpo.put("productoId", evento.getProductoId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(cuerpo);
    }

    /** Tope 500: suficiente para llenar el tablero sin que un clic sature el cluster. */
    @PostMapping("/simular")
    public ResultadoSimulacionDTO simular(
            @RequestParam(defaultValue = "50")
            @Min(value = 1, message = "cantidad debe ser al menos 1")
            @Max(value = 500, message = "cantidad no puede superar 500") int cantidad,
            @RequestParam(required = false) String usuarioId) {
        return simulador.simular(cantidad, usuarioId);
    }
}
