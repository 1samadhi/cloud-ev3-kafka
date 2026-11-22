package com.pedidos360.notificaciones.Repository;

import com.pedidos360.notificaciones.Model.Notificacion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificacionRepositorio extends JpaRepository<Notificacion, String> {

    List<Notificacion> findByUsuarioIdOrderByCreadaEnDesc(String usuarioId, Pageable pagina);

    long countByUsuarioIdAndLeidaFalse(String usuarioId);

    /** Un solo UPDATE en vez de cargar y guardar cada notificación. */
    @Modifying
    @Query("update Notificacion n set n.leida = true where n.usuarioId = :usuarioId and n.leida = false")
    int marcarTodasLeidas(@Param("usuarioId") String usuarioId);
}
