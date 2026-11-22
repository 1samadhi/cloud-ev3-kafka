package com.pedidos360.productos.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Producto tal como lo ve la tienda por REST. Igual al snapshot de Kafka más
 * {@code categoriaEtiqueta}, para que la tienda no tenga que mantener su propio
 * diccionario slug → etiqueta.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductoDTO {
    private String id;
    private String nombre;
    private String marca;
    private String categoria;
    private String categoriaEtiqueta;
    private String subcategoria;
    private Long precio;
    private Integer stock;
    private Double calificacion;
    private String imagenUrl;
    private String descripcion;
    private long actualizadoEn;
}
