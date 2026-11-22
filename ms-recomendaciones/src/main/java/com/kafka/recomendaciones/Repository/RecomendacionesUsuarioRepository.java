package com.kafka.recomendaciones.Repository;

import com.kafka.recomendaciones.DTO.ProductoEnriquecidoDTO;

import java.util.List;
import java.util.Optional;

/** Ultimo top calculado por usuario (tabla RecomendacionesUsuario). */
public interface RecomendacionesUsuarioRepository {

    void guardar(String usuarioId, List<ProductoEnriquecidoDTO> recomendaciones, long calculadoEn);

    /** Vacio si nunca se calculo un top para ese usuario. */
    Optional<List<ProductoEnriquecidoDTO>> obtener(String usuarioId);
}
