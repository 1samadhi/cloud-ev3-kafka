package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Resultado del reset en una partición. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OffsetReseteadoDTO {
    private int particion;
    /** null si el grupo nunca había confirmado offset en esta partición. */
    private Long offsetAnterior;
    private long offsetNuevo;
    /** true si el offset pedido (OFFSET) estaba fuera de rango y se ajustó al inicio o al final. */
    private boolean ajustado;
}
