package com.kafka.recomendaciones.Repository;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.ProductoEnriquecidoDTO;
import com.kafka.recomendaciones.DTO.SnapshotMetricasDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implementacion en memoria de los tres repositorios, activa con
 * app.dynamodb.habilitado=false.
 *
 * Existe para que las pruebas (y un desarrollo sin DynamoDB Local) levanten el
 * contexto completo sin AWS. Respeta el mismo contrato que la version Dynamo:
 * idempotencia por idEvento y orden del mas reciente al mas antiguo.
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "app.dynamodb.habilitado", havingValue = "false")
public class RepositoriosEnMemoria
        implements HistorialEventosRepository, RecomendacionesUsuarioRepository, EventosOperacionesRepository {

    private final Map<String, Map<String, EventoInteraccionDTO>> historial = new ConcurrentHashMap<>();
    private final Map<String, List<ProductoEnriquecidoDTO>> recomendaciones = new ConcurrentHashMap<>();
    private final List<SnapshotMetricasDTO> snapshots = new CopyOnWriteArrayList<>();

    public RepositoriosEnMemoria() {
        log.warn("DynamoDB deshabilitado (app.dynamodb.habilitado=false): historial y recomendaciones "
                + "quedan SOLO en memoria y se pierden al reiniciar");
    }

    @Override
    public void guardar(EventoInteraccionDTO evento) {
        historial.computeIfAbsent(evento.getUsuarioId(), k -> new ConcurrentHashMap<>())
                .put(evento.getIdEvento(), evento);
    }

    @Override
    public List<EventoInteraccionDTO> ultimos(String usuarioId, int limite) {
        List<EventoInteraccionDTO> eventos = new ArrayList<>(historial.getOrDefault(usuarioId, Map.of()).values());
        eventos.sort(Comparator.comparingLong(EventoInteraccionDTO::getTimestamp).reversed());
        return eventos.stream().limit(limite).toList();
    }

    @Override
    public void guardar(String usuarioId, List<ProductoEnriquecidoDTO> top, long calculadoEn) {
        recomendaciones.put(usuarioId, List.copyOf(top));
    }

    @Override
    public Optional<List<ProductoEnriquecidoDTO>> obtener(String usuarioId) {
        return Optional.ofNullable(recomendaciones.get(usuarioId));
    }

    @Override
    public void guardar(SnapshotMetricasDTO snapshot) {
        snapshots.add(snapshot);
    }

    /** Para pruebas: snapshots "publicados" en EventosOperaciones. */
    public List<SnapshotMetricasDTO> snapshotsPublicados() {
        return Collections.unmodifiableList(snapshots);
    }
}
