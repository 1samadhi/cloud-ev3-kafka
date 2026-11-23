package com.pedidos360.notificaciones.Repository;

import com.pedidos360.notificaciones.Model.MensajeFallido;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MensajeFallidoRepositorio extends JpaRepository<MensajeFallido, Long> {

    List<MensajeFallido> findAllByOrderByRecibidoEnDesc(Pageable pagina);
}
