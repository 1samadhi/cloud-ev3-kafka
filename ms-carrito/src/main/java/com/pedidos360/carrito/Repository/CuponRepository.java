package com.pedidos360.carrito.Repository;

import com.pedidos360.carrito.Model.Cupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CuponRepository extends JpaRepository<Cupon, String> {
}
