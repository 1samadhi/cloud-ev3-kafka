package com.kafka.recomendaciones.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot completo de un producto en catalogo-productos (§2.3). La key del
 * mensaje es el id; un valor null (tombstone) significa "producto eliminado".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductoCatalogoDTO {

    @NotBlank(message = "id es obligatorio")
    private String id;

    @NotBlank(message = "nombre es obligatorio")
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
