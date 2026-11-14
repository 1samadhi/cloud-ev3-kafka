package com.kafka.recomendaciones.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricasDTO {
    private List<ProductoVistoDTO> productosMasVistos;
    private long interaccionesPorMinuto;
    private long usuariosActivos;
    private long totalInteracciones;
}
