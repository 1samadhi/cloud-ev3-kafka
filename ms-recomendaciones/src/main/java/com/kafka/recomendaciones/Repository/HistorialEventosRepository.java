package com.kafka.recomendaciones.Repository;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;

import java.util.List;

/**
 * Historial de eventos por usuario. Interfaz para que el servicio no sepa si
 * detras hay DynamoDB (produccion) o memoria (pruebas sin AWS).
 */
public interface HistorialEventosRepository {

    /** Idempotente por idEvento: guardar dos veces el mismo evento deja un solo registro. */
    void guardar(EventoInteraccionDTO evento);

    /** Los ultimos {@code limite} eventos del usuario, del mas reciente al mas antiguo. */
    List<EventoInteraccionDTO> ultimos(String usuarioId, int limite);
}
