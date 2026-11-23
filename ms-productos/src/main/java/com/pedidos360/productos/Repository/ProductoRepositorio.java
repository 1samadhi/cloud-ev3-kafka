package com.pedidos360.productos.Repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pedidos360.productos.Model.Producto;

import jakarta.persistence.LockModeType;

public interface ProductoRepositorio extends JpaRepository<Producto, String> {

    /**
     * SELECT ... FOR UPDATE de las filas a modificar, SIEMPRE ordenadas por id.
     *
     * El bloqueo serializa a las reservas que compiten por el mismo producto (la segunda
     * espera y luego ve el stock ya descontado), y el orden fijo evita el deadlock clásico:
     * si el pedido A bloqueara 101→110 y el B 110→101, cada uno esperaría al otro.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Producto p where p.id in :ids order by p.id")
    List<Producto> bloquearPorIds(@Param("ids") Collection<String> ids);

    List<Producto> findAllByOrderByIdAsc();

    List<Producto> findByCategoriaOrderByIdAsc(String categoria);

    @Query("select p.categoria, count(p) from Producto p group by p.categoria")
    List<Object[]> contarPorCategoria();
}
