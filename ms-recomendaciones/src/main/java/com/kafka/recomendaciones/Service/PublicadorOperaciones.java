package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.DTO.SnapshotMetricasDTO;
import com.kafka.recomendaciones.Repository.EventosOperacionesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Escribe el snapshot de metricas en EventosOperaciones cada 5 s, solo con
 * app.dynamodb.publicar-operaciones=true (§5.2).
 *
 * Cada escritura dispara la Lambda Procesador via Streams, y cada invocacion y
 * postToConnection se cobran. Por eso se omite la escritura cuando las metricas
 * no cambiaron desde la ultima publicada: con la tienda quieta no se paga nada,
 * y la consola ya tiene el ultimo valor.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.dynamodb.publicar-operaciones", havingValue = "true")
public class PublicadorOperaciones {

    private final MetricasEnVivo metricas;
    private final EventosOperacionesRepository repositorio;
    private Integer ultimaFirma;

    public PublicadorOperaciones(MetricasEnVivo metricas, EventosOperacionesRepository repositorio) {
        this.metricas = metricas;
        this.repositorio = repositorio;
        log.info("Publicacion de snapshots en EventosOperaciones ACTIVA");
    }

    @Scheduled(fixedRateString = "${app.dynamodb.operaciones-ms:5000}", initialDelay = 5000)
    public void publicarProgramado() {
        publicar();
    }

    /** @return true si escribio. */
    public synchronized boolean publicar() {
        SnapshotMetricasDTO snapshot = metricas.snapshot();
        int firma = firma(snapshot);
        if (ultimaFirma != null && ultimaFirma == firma) {
            return false;
        }
        try {
            repositorio.guardar(snapshot);
            ultimaFirma = firma;
            log.info("Snapshot de metricas escrito en EventosOperaciones: {} int/min, {} usuarios activos",
                    snapshot.getInteraccionesPorMinuto(), snapshot.getUsuariosActivos());
            return true;
        } catch (RuntimeException e) {
            // Telemetria: si DynamoDB falla se pierde un snapshot, no el negocio. El
            // siguiente ciclo reintenta porque la firma no se actualizo.
            log.error("No se pudo escribir el snapshot en EventosOperaciones: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Firma sin el timestamp ni el inicio de cada minuto (cambian siempre): solo
     * los valores que el tablero muestra.
     */
    static int firma(SnapshotMetricasDTO s) {
        List<Long> serie = s.getSerieMinutos().stream().map(SnapshotMetricasDTO.PuntoSerie::getInteracciones).toList();
        return Objects.hash(s.getInteraccionesPorMinuto(), s.getUsuariosActivos(), s.getTotalInteracciones(),
                s.getTopProductos(), s.getEmbudo(), s.getPorTipo(), serie);
    }
}
