package com.pedidos360.notificaciones.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Auditoría de TODO lo que pasa por el exchange (binding "#"). Sólo log INFO:
 * demuestra que una misma publicación llega a dos colas distintas por patrón
 * (cliente y auditoría) sin que el productor lo sepa.
 */
@Slf4j
@Service
public class ServicioAuditoria {

    /** El log no necesita el cuerpo completo; evita líneas de varios KB. */
    private static final int MAX_CUERPO_LOG = 500;

    public void registrar(String routingKey, String messageId, String cuerpo) {
        String recortado = cuerpo == null ? "" : cuerpo.length() > MAX_CUERPO_LOG
                ? cuerpo.substring(0, MAX_CUERPO_LOG) + "...(recortado)"
                : cuerpo;
        log.info("[AUDITORIA] routingKey={} messageId={} cuerpo={}", routingKey, messageId, recortado);
    }
}
