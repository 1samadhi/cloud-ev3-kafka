package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO para representar el detalle de una partición específica.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParticionDetalleDTO {
    private int numero;
    private int lider;
    private List<Integer> replicas;
    private List<Integer> isr;
}
