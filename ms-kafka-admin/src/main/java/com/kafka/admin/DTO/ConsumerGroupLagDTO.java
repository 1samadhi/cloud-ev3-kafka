package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO para representar la respuesta de lag de un consumer group.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerGroupLagDTO {
    private String groupId;
    private long lagTotal;
    private List<LagDetalleDTO> detalles;
}
