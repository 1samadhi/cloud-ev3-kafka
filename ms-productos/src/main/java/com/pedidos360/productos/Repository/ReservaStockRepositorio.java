package com.pedidos360.productos.Repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pedidos360.productos.Model.ReservaStock;

import jakarta.persistence.LockModeType;

public interface ReservaStockRepositorio extends JpaRepository<ReservaStock, String> {

    /**
     * Bloquea la reserva antes de liberarla: dos liberaciones simultáneas del mismo pedido
     * (reintento de ms-carrito) se serializan y la segunda ve LIBERADA, así el stock se
     * devuelve una sola vez.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReservaStock r where r.idPedido = :idPedido")
    Optional<ReservaStock> bloquearPorIdPedido(@Param("idPedido") String idPedido);
}
