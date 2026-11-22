package com.kafka.recomendaciones.Repository;

import com.kafka.recomendaciones.DTO.SnapshotMetricasDTO;

/** Escritura del snapshot de metricas en EventosOperaciones (dispara la Lambda via Streams). */
public interface EventosOperacionesRepository {

    void guardar(SnapshotMetricasDTO snapshot);
}
