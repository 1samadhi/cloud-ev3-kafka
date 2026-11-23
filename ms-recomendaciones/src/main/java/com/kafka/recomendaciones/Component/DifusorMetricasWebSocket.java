package com.kafka.recomendaciones.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafka.recomendaciones.Service.MetricasEnVivo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler de /ws/metricas: empuja el snapshot de MetricasEnVivo a cada cliente
 * conectado cada app.metricas.difusion-ms (2 s).
 *
 * El snapshot se serializa UNA vez por ciclo y se manda el mismo texto a todas
 * las sesiones. Cada sesion va envuelta en ConcurrentWebSocketSessionDecorator:
 * el envio inicial (hilo HTTP) y el programado (hilo scheduler) pueden coincidir,
 * y una WebSocketSession de Tomcat no admite dos envios simultaneos. El
 * decorador ademas corta a un cliente lento (5 s o 512 KB en cola) en vez de
 * frenar la difusion para todos.
 */
@Slf4j
@Component
public class DifusorMetricasWebSocket extends TextWebSocketHandler {

    private static final int LIMITE_ENVIO_MS = 5_000;
    private static final int LIMITE_BUFFER_BYTES = 512 * 1024;

    private final MetricasEnVivo metricas;
    private final ObjectMapper mapper;
    private final Map<String, WebSocketSession> sesiones = new ConcurrentHashMap<>();

    public DifusorMetricasWebSocket(MetricasEnVivo metricas, ObjectMapper mapper) {
        this.metricas = metricas;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession sesion) {
        WebSocketSession segura = new ConcurrentWebSocketSessionDecorator(sesion, LIMITE_ENVIO_MS, LIMITE_BUFFER_BYTES);
        sesiones.put(sesion.getId(), segura);
        log.info("Dashboard conectado a /ws/metricas sesion={} remoto={} (abiertas={})",
                sesion.getId(), sesion.getRemoteAddress(), sesiones.size());
        // Snapshot inmediato: el tablero no queda 2 s en blanco al conectar.
        enviar(segura, serializar());
    }

    /** El canal es solo de bajada: lo que mande el cliente se ignora. */
    @Override
    protected void handleTextMessage(WebSocketSession sesion, TextMessage mensaje) {
        log.debug("Mensaje del cliente ignorado en /ws/metricas sesion={}", sesion.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession sesion, Throwable error) {
        log.warn("Error de transporte en /ws/metricas sesion={}: {}", sesion.getId(), error.getMessage());
        sesiones.remove(sesion.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession sesion, CloseStatus estado) {
        sesiones.remove(sesion.getId());
        log.info("Dashboard desconectado sesion={} estado={} (abiertas={})", sesion.getId(), estado, sesiones.size());
    }

    @Scheduled(fixedRateString = "${app.metricas.difusion-ms:2000}", initialDelayString = "${app.metricas.difusion-ms:2000}")
    public void difundir() {
        if (sesiones.isEmpty()) {
            return; // sin oyentes no se calcula nada
        }
        String json = serializar();
        if (json == null) {
            return;
        }
        sesiones.values().forEach(s -> enviar(s, json));
    }

    public int sesionesAbiertas() {
        return sesiones.size();
    }

    private String serializar() {
        try {
            return mapper.writeValueAsString(metricas.snapshot());
        } catch (JsonProcessingException e) {
            log.error("No se pudo serializar el snapshot de metricas: {}", e.getMessage());
            return null;
        }
    }

    private void enviar(WebSocketSession sesion, String json) {
        if (json == null || !sesion.isOpen()) {
            sesiones.remove(sesion.getId());
            return;
        }
        try {
            sesion.sendMessage(new TextMessage(json));
        } catch (IOException | RuntimeException e) {
            // Un cliente caido no debe cortar la difusion a los demas.
            log.warn("No se pudo enviar metricas a sesion={}: {}. Se cierra.", sesion.getId(), e.getMessage());
            sesiones.remove(sesion.getId());
            try {
                sesion.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignorada) {
                // ya estaba cerrada
            }
        }
    }
}
