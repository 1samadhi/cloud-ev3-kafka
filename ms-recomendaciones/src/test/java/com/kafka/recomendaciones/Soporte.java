package com.kafka.recomendaciones;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** Utilidades compartidas por las pruebas unitarias. */
public final class Soporte {

    private Soporte() {
    }

    public static EventoInteraccionDTO evento(String usuario, TipoEvento tipo, String productoId, long ts) {
        return EventoInteraccionDTO.builder()
                .idEvento(UUID.randomUUID().toString())
                .usuarioId(usuario)
                .tipoEvento(tipo)
                .productoId(productoId)
                .origen("prueba")
                .detalle(Map.of())
                .timestamp(ts)
                .build();
    }

    /** Reloj que se mueve a mano: ventanas y decaimiento se prueban sin Thread.sleep. */
    public static final class RelojMutable extends Clock {
        private long ahora;

        public RelojMutable(long inicio) {
            this.ahora = inicio;
        }

        public void avanzar(long ms) {
            ahora += ms;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(ahora);
        }

        @Override
        public long millis() {
            return ahora;
        }
    }
}
