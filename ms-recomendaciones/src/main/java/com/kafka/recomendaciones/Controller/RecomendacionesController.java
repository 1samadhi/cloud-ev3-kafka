package com.kafka.recomendaciones.Controller;

import com.kafka.recomendaciones.DTO.ProductoEnriquecidoDTO;
import com.kafka.recomendaciones.Exception.PayloadInvalidoException;
import com.kafka.recomendaciones.Service.ServicioRecomendaciones;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** GET /api/recomendaciones?userId= : el ultimo top 5 con motivo (contrato historico + campos nuevos). */
@RestController
@RequestMapping("/api/recomendaciones")
public class RecomendacionesController {

    private final ServicioRecomendaciones servicio;

    public RecomendacionesController(ServicioRecomendaciones servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<ProductoEnriquecidoDTO> obtener(@RequestParam("userId") String userId) {
        if (userId.isBlank()) {
            throw new PayloadInvalidoException("userId es obligatorio");
        }
        return servicio.obtener(userId.trim());
    }
}
