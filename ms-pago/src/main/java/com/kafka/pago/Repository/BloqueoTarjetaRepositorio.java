package com.kafka.pago.Repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.kafka.pago.Model.BloqueoTarjeta;

@Repository
public interface BloqueoTarjetaRepositorio extends JpaRepository<BloqueoTarjeta, String> {

    long countByBloqueadoHastaAfter(Instant ahora);

    /** Máximo 20 para el panel; consulta derivada, portable entre MySQL y H2 (antes era LIMIT nativo). */
    List<BloqueoTarjeta> findTop20ByBloqueadoHastaAfterOrderByActualizadoEnDesc(Instant ahora);
}
