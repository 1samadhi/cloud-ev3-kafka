package com.pedidos360.carrito.Repository;

import com.pedidos360.carrito.Model.EstadoPedido;
import com.pedidos360.carrito.Model.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PedidoRepository extends JpaRepository<Pedido, String> {

    List<Pedido> findByUsuarioIdOrderByCreadoEnDesc(String usuarioId);

    List<Pedido> findAllByOrderByCreadoEnDesc();

    List<Pedido> findByEstadoOrderByCreadoEnDesc(EstadoPedido estado);

    List<Pedido> findByEstadoIn(Collection<EstadoPedido> estados);

    /** Sólo ids: el scheduler avanza cada pedido en su propia transacción, cargándolo fresco. */
    @Query("select p.id from Pedido p where p.estado in :estados order by p.actualizadoEn asc")
    List<String> idsPorEstado(@Param("estados") Collection<EstadoPedido> estados);
}
