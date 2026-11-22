package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Respuesta de POST /api/consumer-groups/{id}/offsets/reset. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResultadoResetOffsetsDTO {
    private String groupId;
    private String topico;
    private EstrategiaReset estrategia;
    private List<OffsetReseteadoDTO> particiones;
}
