package com.kafka.recomendaciones.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Producto {
    private String id;
    private String nombre;
    private String categoria;
    private String productType;
    private String marca;
    private double precio;
    private double calificacion;
    private String imagenUrl;
    private int stock;
}
