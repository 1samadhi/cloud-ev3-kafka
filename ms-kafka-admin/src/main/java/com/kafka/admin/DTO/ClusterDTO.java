package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO para representar el resumen del cluster.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClusterDTO {
    private int cantidadBrokers;
    private List<BrokerDTO> brokers;
    private int controlador;
}
