package com.kafka.pago.Repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.kafka.pago.Model.IntentoTarjeta;

@Repository
public interface IntentoTarjetaRepositorio extends JpaRepository<IntentoTarjeta, Long> {

    boolean existsByIdIntento(String idIntento);

    /**
     * Instantes de una huella dentro de (desde, hasta]. Devuelve sólo los
     * instantes, que es todo lo que la ventana necesita para decidir.
     */
    @Query("SELECT i.instante FROM IntentoTarjeta i "
            + "WHERE i.huella = :huella AND i.instante > :desde AND i.instante <= :hasta")
    List<Instant> instantesEnVentana(@Param("huella") String huella,
                                     @Param("desde") Instant desde,
                                     @Param("hasta") Instant hasta);

    long countByHuella(String huella);

    @Query("SELECT COUNT(DISTINCT i.huella) FROM IntentoTarjeta i")
    long contarHuellasDistintas();

    @Modifying
    @Query("DELETE FROM IntentoTarjeta i WHERE i.instante < :limite")
    int borrarAnterioresA(@Param("limite") Instant limite);

    @Modifying
    @Query("DELETE FROM IntentoTarjeta i WHERE i.huella = :huella")
    int borrarPorHuella(@Param("huella") String huella);
}
