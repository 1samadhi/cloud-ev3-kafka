package com.pedidos360.notificaciones.Service;

import com.pedidos360.notificaciones.DTO.NotificacionDTO;
import com.pedidos360.notificaciones.DTO.NotificacionRespuestaDTO;
import com.pedidos360.notificaciones.Exception.NotificacionNoEncontradaException;
import com.pedidos360.notificaciones.Model.Notificacion;
import com.pedidos360.notificaciones.Repository.NotificacionRepositorio;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * Lógica de notificaciones, separada del listener: el listener sólo sabe de
 * RabbitMQ, este servicio sólo sabe de notificaciones. Así se prueba sin broker.
 */
@Slf4j
@Service
public class ServicioNotificaciones {

    private final NotificacionRepositorio repositorio;
    private final EmisorSse emisorSse;
    private final int limiteListado;

    public ServicioNotificaciones(NotificacionRepositorio repositorio, EmisorSse emisorSse,
                                  @Value("${app.notificaciones.limite-listado:50}") int limiteListado) {
        this.repositorio = repositorio;
        this.emisorSse = emisorSse;
        this.limiteListado = limiteListado;
    }

    /**
     * Persiste y empuja por SSE. Sin @Transactional a propósito: el save hace
     * commit antes de emitir, así el navegador nunca ve una notificación que
     * después no aparece en el listado por un rollback.
     *
     * Un error de base (DataAccessException) se propaga: es transitorio, el
     * contenedor lo reintenta y, si persiste, el mensaje termina en la DLQ.
     *
     * @return vacío si era un duplicado (reentrega at-least-once) y se ignoró.
     */
    public Optional<NotificacionRespuestaDTO> procesar(NotificacionDTO dto) {
        if (repositorio.existsById(dto.getIdNotificacion())) {
            log.info("Notificación duplicada ignorada (reentrega): idNotificacion={} usuarioId={}",
                    dto.getIdNotificacion(), dto.getUsuarioId());
            return Optional.empty();
        }

        Notificacion notificacion = Notificacion.builder()
                .id(dto.getIdNotificacion())
                .usuarioId(dto.getUsuarioId())
                .tipo(dto.getTipo())
                .titulo(dto.getTitulo())
                .mensaje(dto.getMensaje())
                .idPedido(dto.getIdPedido())
                .datos(dto.getDatos() == null ? new HashMap<>() : new HashMap<>(dto.getDatos()))
                .leida(false)
                .creadaEn(dto.getTimestamp() != null && dto.getTimestamp() > 0
                        ? Instant.ofEpochMilli(dto.getTimestamp())
                        : Instant.now())
                .build();

        Notificacion guardada;
        try {
            guardada = repositorio.save(notificacion);
        } catch (DataIntegrityViolationException e) {
            // Dos consumidores recibieron el mismo mensaje a la vez: el segundo
            // INSERT choca con la PK. No es un error, es la idempotencia actuando.
            log.info("Notificación duplicada ignorada (carrera de PK): idNotificacion={}", dto.getIdNotificacion());
            return Optional.empty();
        }

        NotificacionRespuestaDTO respuesta = NotificacionRespuestaDTO.desde(guardada);
        int entregadas = emisorSse.emitir(guardada.getUsuarioId(), respuesta);
        log.info("Notificación guardada: id={} usuarioId={} tipo={} idPedido={} conexionesSse={}",
                guardada.getId(), guardada.getUsuarioId(), guardada.getTipo(), guardada.getIdPedido(), entregadas);
        return Optional.of(respuesta);
    }

    @Transactional(readOnly = true)
    public List<NotificacionRespuestaDTO> listar(String usuarioId) {
        return repositorio.findByUsuarioIdOrderByCreadaEnDesc(usuarioId, PageRequest.of(0, limiteListado))
                .stream()
                .map(NotificacionRespuestaDTO::desde)
                .toList();
    }

    @Transactional
    public NotificacionRespuestaDTO marcarLeida(String id) {
        Notificacion notificacion = repositorio.findById(id)
                .orElseThrow(() -> new NotificacionNoEncontradaException(id));
        notificacion.setLeida(true);
        return NotificacionRespuestaDTO.desde(notificacion);
    }

    @Transactional
    public int marcarTodasLeidas(String usuarioId) {
        int actualizadas = repositorio.marcarTodasLeidas(usuarioId);
        log.info("Notificaciones marcadas como leídas: usuarioId={} cantidad={}", usuarioId, actualizadas);
        return actualizadas;
    }
}
