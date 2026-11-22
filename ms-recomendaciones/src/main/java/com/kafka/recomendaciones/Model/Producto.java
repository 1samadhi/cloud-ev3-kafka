package com.kafka.recomendaciones.Model;

import com.kafka.recomendaciones.DTO.ProductoCatalogoDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Producto del catalogo en memoria (campos de §7.1). Es el modelo con el que
 * trabaja el motor; el DTO de Kafka se convierte aca para que un cambio en el
 * mensaje no obligue a tocar la logica.
 */
@Data
// toBuilder: copiar un producto cambiando un campo (p. ej. stock) sin repetir los demas.
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Producto {
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

    public static Producto desde(ProductoCatalogoDTO dto) {
        return Producto.builder()
                .id(dto.getId())
                .nombre(dto.getNombre())
                .marca(dto.getMarca())
                .categoria(dto.getCategoria())
                .subcategoria(dto.getSubcategoria())
                .precio(dto.getPrecio())
                .stock(dto.getStock())
                .calificacion(dto.getCalificacion())
                .imagenUrl(dto.getImagenUrl())
                .descripcion(dto.getDescripcion())
                .actualizadoEn(dto.getActualizadoEn())
                .build();
    }

    /** Stock desconocido (null) se trata como disponible: no se esconde lo que no se sabe. */
    public boolean tieneStock() {
        return stock == null || stock > 0;
    }
}
