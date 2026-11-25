package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Respuesta de GET /api/consumer-groups/{id}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerGroupDetalleDTO {
    private String id;
    /** Stable, Empty, PreparingRebalance, CompletingRebalance... */
    private String estado;
    /** Broker que coordina el grupo (guarda sus offsets); null si no se conoce. */
    private BrokerDTO coordinador;
    /** Asignador de particiones (range, cooperative-sticky...). */
    private String estrategia;
    private boolean grupoSimple;
    private int cantidadMiembros;
    private List<MiembroConsumerGroupDTO> miembros;
}
