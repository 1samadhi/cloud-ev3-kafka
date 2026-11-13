package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para la solicitud de creación de un tópico.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrearTopicoRequestDTO {
    private String nombre;
    private int particiones;
    private int replicas;
    private Long retentionMs;
    private String cleanupPolicy;
    private Integer minInsyncReplicas;
}
