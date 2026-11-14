package com.kafka.pago.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO que contiene el resumen de alertas de fraude para el dashboard de operaciones.
 * Incluye contadores y lista de bloqueos vigentes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumenAlertasDTO {

    private int bloqueosActivos;
    private int totalClientesVigilados;
    private List<AlertaFraudeDTO> alertas;
}
