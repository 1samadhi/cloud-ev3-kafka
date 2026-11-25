package com.pedidos360.carrito.Repository;

import com.pedidos360.carrito.Model.Carrito;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarritoRepository extends JpaRepository<Carrito, String> {
}
