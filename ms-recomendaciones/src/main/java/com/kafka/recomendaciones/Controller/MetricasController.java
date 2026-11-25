package com.kafka.recomendaciones.Controller;

import com.kafka.recomendaciones.DTO.SnapshotMetricasDTO;
import com.kafka.recomendaciones.Service.MetricasEnVivo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/metricas: el MISMO snapshot que difunde /ws/metricas. Lo usa el modo
 * "Leer de los servicios" de la consola (sondeo), que lee productosMasVistos,
 * interaccionesPorMinuto, usuariosActivos y totalInteracciones.
 */
@RestController
@RequestMapping("/api/metricas")
public class MetricasController {

    private final MetricasEnVivo metricas;

    public MetricasController(MetricasEnVivo metricas) {
        this.metricas = metricas;
    }

    @GetMapping
    public SnapshotMetricasDTO obtener() {
        return metricas.snapshot();
    }
}
