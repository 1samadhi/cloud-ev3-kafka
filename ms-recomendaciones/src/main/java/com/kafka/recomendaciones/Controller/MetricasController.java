package com.kafka.recomendaciones.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.kafka.recomendaciones.DTO.MetricasDTO;
import com.kafka.recomendaciones.Service.MetricasService;

/**
 * Controlador REST para exponer las métricas de operación del servicio.
 * Endpoint: GET /api/metricas
 *
 * CORS habilitado para permitir consumo desde frontend local (file://).
 */
@RestController
@RequestMapping("/api/metricas")
public class MetricasController {

    @Autowired
    private MetricasService metricasService;

    /**
     * Obtiene las métricas en tiempo real para el dashboard.
     *
     * @return MetricasDTO con productosMasVistos, interaccionesPorMinuto,
     *         usuariosActivos y totalInteracciones.
     */
    @GetMapping
    public MetricasDTO obtenerMetricas() {
        return metricasService.obtenerMetricas();
    }
}
