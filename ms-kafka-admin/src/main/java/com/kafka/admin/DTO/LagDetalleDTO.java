package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para representar el detalle de lag de una partición en un consumer group.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LagDetalleDTO {
    private String topico;
    private int particion;
    private long offsetActual;
    private long offsetFinal;
    private long lag;
}
