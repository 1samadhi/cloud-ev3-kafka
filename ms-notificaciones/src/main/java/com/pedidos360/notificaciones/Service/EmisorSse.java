package com.pedidos360.notificaciones.Service;

import com.pedidos360.notificaciones.DTO.NotificacionRespuestaDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Conexiones SSE abiertas, agrupadas por usuario.
 *
 * Un usuario puede tener varias pestañas: cada una es un SseEmitter y todas
 * reciben la notificación. Estructuras concurrentes porque emiten los hilos del
 * listener RabbitMQ mientras los hilos HTTP agregan y quitan conexiones.
 *
 * Por qué SSE y no WebSocket: el flujo es sólo servidor -> navegador, EventSource
 * reconecta solo y pasa por cualquier proxy HTTP. El estado es por instancia: con
 * varias réplicas de este servicio cada una empuja sólo a sus conexiones (para
 * la demo hay una; escalar requeriría una cola por instancia).
 */
@Slf4j
@Service
public class EmisorSse {

    private final Map<String, Set<SseEmitter>> emisoresPorUsuario = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public EmisorSse(@Value("${app.sse.timeout-ms:1800000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public SseEmitter suscribir(String usuarioId) {
        SseEmitter emisor = new SseEmitter(timeoutMs);
        emisoresPorUsuario.computeIfAbsent(usuarioId, k -> new CopyOnWriteArraySet<>()).add(emisor);

        emisor.onCompletion(() -> quitar(usuarioId, emisor));
        // complete() en el timeout: si no, Spring lanza AsyncRequestTimeoutException
        // e intenta escribir un error JSON en un stream text/event-stream.
        emisor.onTimeout(emisor::complete);
        emisor.onError(error -> quitar(usuarioId, emisor));

        try {
            // Primer evento inmediato: el navegador confirma la conexión (y los
            // proxies dejan de bufferear) sin esperar a la primera notificación.
            emisor.send(SseEmitter.event().name("conectado").data(Map.of("usuarioId", usuarioId)));
        } catch (IOException e) {
            quitar(usuarioId, emisor);
        }
        log.info("SSE abierto: usuarioId={} conexionesDelUsuario={}", usuarioId, conexionesActivas(usuarioId));
        return emisor;
    }

    /**
     * Empuja la notificación a todas las conexiones del usuario.
     *
     * @return cuántas conexiones la recibieron (0 si el usuario no está conectado:
     *         no es un error, la notificación ya quedó guardada para el listado).
     */
    public int emitir(String usuarioId, NotificacionRespuestaDTO notificacion) {
        Set<SseEmitter> emisores = emisoresPorUsuario.getOrDefault(usuarioId, Set.of());
        int entregadas = 0;
        for (SseEmitter emisor : emisores) {
            try {
                emisor.send(SseEmitter.event()
                        .name("notificacion")
                        .id(notificacion.id())
                        .data(notificacion));
                entregadas++;
            } catch (IOException | IllegalStateException e) {
                // Pestaña cerrada sin avisar: se descarta esa conexión, no se
                // propaga (un navegador caído no debe mandar el mensaje a la DLQ).
                log.debug("Conexión SSE muerta para usuarioId={}: {}", usuarioId, e.getMessage());
                quitar(usuarioId, emisor);
            }
        }
        return entregadas;
    }

    /** Comentario SSE periódico: mantiene viva la conexión a través de proxies. */
    @Scheduled(fixedRateString = "${app.sse.heartbeat-ms:20000}")
    public void latido() {
        emisoresPorUsuario.forEach((usuarioId, emisores) -> {
            for (SseEmitter emisor : emisores) {
                try {
                    emisor.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException e) {
                    quitar(usuarioId, emisor);
                }
            }
        });
    }

    public int conexionesActivas(String usuarioId) {
        return emisoresPorUsuario.getOrDefault(usuarioId, Set.of()).size();
    }

    private void quitar(String usuarioId, SseEmitter emisor) {
        emisoresPorUsuario.computeIfPresent(usuarioId, (k, emisores) -> {
            emisores.remove(emisor);
            return emisores.isEmpty() ? null : emisores;
        });
    }
}
