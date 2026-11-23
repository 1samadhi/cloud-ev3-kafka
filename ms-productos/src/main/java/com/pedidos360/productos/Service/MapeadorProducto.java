package com.pedidos360.productos.Service;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.pedidos360.productos.DTO.ProductoCatalogoDTO;
import com.pedidos360.productos.DTO.ProductoDTO;
import com.pedidos360.productos.Model.Categoria;
import com.pedidos360.productos.Model.Producto;
import com.pedidos360.productos.Repository.CategoriaRepositorio;

import lombok.RequiredArgsConstructor;

/** Conversión entidad → DTOs (REST y snapshot de Kafka), en un solo lugar. */
@Component
@RequiredArgsConstructor
public class MapeadorProducto {

    private final CategoriaRepositorio categorias;

    /** slug → etiqueta; son 8 filas, se leen por llamada para no mantener una caché que invalidar. */
    public Map<String, String> etiquetas() {
        return categorias.findAll().stream().collect(Collectors.toMap(Categoria::getSlug, Categoria::getEtiqueta));
    }

    public ProductoDTO aDTO(Producto p, Map<String, String> etiquetas) {
        return ProductoDTO.builder()
                .id(p.getId())
                .nombre(p.getNombre())
                .marca(p.getMarca())
                .categoria(p.getCategoria())
                // Si la categoría no está en la tabla se devuelve el slug: nunca un null que rompa la tienda.
                .categoriaEtiqueta(etiquetas.getOrDefault(p.getCategoria(), p.getCategoria()))
                .subcategoria(p.getSubcategoria())
                .precio(p.getPrecio())
                .stock(p.getStock())
                .calificacion(p.getCalificacion())
                .imagenUrl(p.getImagenUrl())
                .descripcion(p.getDescripcion())
                .actualizadoEn(p.getActualizadoEn().toEpochMilli())
                .build();
    }

    public ProductoDTO aDTO(Producto p) {
        return aDTO(p, etiquetas());
    }

    public static ProductoCatalogoDTO aSnapshot(Producto p) {
        return ProductoCatalogoDTO.builder()
                .id(p.getId())
                .nombre(p.getNombre())
                .marca(p.getMarca())
                .categoria(p.getCategoria())
                .subcategoria(p.getSubcategoria())
                .precio(p.getPrecio())
                .stock(p.getStock())
                .calificacion(p.getCalificacion())
                .imagenUrl(p.getImagenUrl())
                .descripcion(p.getDescripcion())
                .actualizadoEn(p.getActualizadoEn().toEpochMilli())
                .build();
    }
}
