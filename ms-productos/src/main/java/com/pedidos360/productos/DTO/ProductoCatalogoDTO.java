package com.pedidos360.productos.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot COMPLETO de un producto → tópico compactado {@code catalogo-productos}
 * (contrato §2.3). Siempre se manda el producto entero, nunca un delta: con
 * cleanup.policy=compact sólo sobrevive el último mensaje de cada key, así que ese
 * último mensaje tiene que bastar por sí solo para reconstruir el producto.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductoCatalogoDTO {
    private String id;
    private String nombre;
    private String marca;
    private String categoria;
    private String subcategoria;
    private Long precio;
    private Integer stock;
    private Double calificacion;
    private String imagenUrl;
    private String descripcion;
    private long actualizadoEn;
}
