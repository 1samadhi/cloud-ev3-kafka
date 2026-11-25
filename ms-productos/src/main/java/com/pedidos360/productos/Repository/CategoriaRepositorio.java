package com.pedidos360.productos.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pedidos360.productos.Model.Categoria;

public interface CategoriaRepositorio extends JpaRepository<Categoria, String> {

    List<Categoria> findAllByOrderByOrdenAsc();
}
