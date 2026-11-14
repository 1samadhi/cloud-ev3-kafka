package com.kafka.recomendaciones.Controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.kafka.recomendaciones.DTO.ProductoEnriquecidoDTO;
import com.kafka.recomendaciones.Service.RecomendacionesService;

import java.util.List;

/**
 * Consulta del Top 5 de recomendaciones de un usuario.
 *
 * CORRECCION A LA GUIA: la guia devolvia la entidad JPA "Recomendaciones" cruda,
 * que solo trae itemId, score y rankPosition. El frontend no puede mostrar un
 * producto con eso: le faltan nombre, marca, precio e imagen. La guia ademas
 * define ProductoEnriquecidoDTO y nunca lo usa.
 *
 * Aca se devuelve el DTO enriquecido cruzando cada recomendacion con el catalogo.
 * Devolver el DTO y no la entidad tambien evita exponer la forma de la tabla en
 * la API, que es lo que corresponde.
 */
@RestController
@RequestMapping("/api/recomendaciones")
public class RecomendacionesController {

    private final RecomendacionesService recomendacionesServices;

    // Inyeccion por constructor: si falta un bean el fallo es al arrancar y no
    // un NullPointerException en la primera peticion.
    public RecomendacionesController(RecomendacionesService recomendacionesServices) {
        this.recomendacionesServices = recomendacionesServices;
    }

    @GetMapping
    public ResponseEntity<List<ProductoEnriquecidoDTO>> getRecommendations(
            @RequestParam("userId") String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(recomendacionesServices.obtenerTop5Enriquecido(userId));
    }
}
