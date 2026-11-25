package com.kafka.recomendaciones.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Una recomendacion tal como la consume la consola (GET /api/recomendaciones).
 *
 * Conserva TODOS los campos historicos (rankPosition, score, itemId, nombre,
 * categoria, marca, precio, calificacion, imagenUrl) porque la tabla de la
 * consola los lee, y agrega motivo, subcategoria y stock (§5.2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductoEnriquecidoDTO {
    private int rankPosition;
    private double score;
    private String itemId;
    private String nombre;
    private String categoria;
    private String marca;
    private double precio;
    private double calificacion;
    private String imagenUrl;
    /** Explicacion legible: "Porque viste Galaxy S24", "Te interesa Audio (62 %)"... */
    private String motivo;
    private String subcategoria;
    private Integer stock;
}
