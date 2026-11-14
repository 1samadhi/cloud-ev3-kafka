package com.kafka.recomendaciones.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductoVistoDTO {
    private String itemId;
    private String nombre;
    private String categoria;
    private long vistas;
}
