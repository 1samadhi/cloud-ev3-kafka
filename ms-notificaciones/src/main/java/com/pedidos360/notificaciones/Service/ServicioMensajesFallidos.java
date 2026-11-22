package com.pedidos360.notificaciones.Service;

import com.pedidos360.notificaciones.DTO.MensajeFallidoDTO;
import com.pedidos360.notificaciones.DTO.MensajeMuertoDTO;
import com.pedidos360.notificaciones.Model.MensajeFallido;
import com.pedidos360.notificaciones.Repository.MensajeFallidoRepositorio;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Guarda los mensajes muertos de la DLQ en la tabla mensaje_fallido. */
@Service
@RequiredArgsConstructor
public class ServicioMensajesFallidos {

    /** Suficiente para la vista admin; los más viejos siguen en la tabla. */
    private static final int LIMITE_LISTADO = 100;

    private final MensajeFallidoRepositorio repositorio;

    @Transactional
    public MensajeFallido registrar(MensajeMuertoDTO muerto) {
        MensajeFallido fallido = MensajeFallido.builder()
                .messageId(muerto.messageId())
                .colaOrigen(muerto.colaOrigen())
                .exchangeOrigen(muerto.exchangeOrigen())
                .routingKeys(recortar(String.join(",", muerto.routingKeys()), 500))
                .motivo(muerto.motivo())
                .conteo(muerto.conteo())
                .cuerpo(recortar(muerto.cuerpo(), 4000))
                .recibidoEn(Instant.now())
                .build();
        return repositorio.save(fallido);
    }

    @Transactional(readOnly = true)
    public List<MensajeFallidoDTO> listar() {
        return repositorio.findAllByOrderByRecibidoEnDesc(PageRequest.of(0, LIMITE_LISTADO))
                .stream()
                .map(MensajeFallidoDTO::desde)
                .toList();
    }

    private static String recortar(String texto, int maximo) {
        if (texto == null || texto.length() <= maximo) {
            return texto;
        }
        return texto.substring(0, maximo);
    }
}
