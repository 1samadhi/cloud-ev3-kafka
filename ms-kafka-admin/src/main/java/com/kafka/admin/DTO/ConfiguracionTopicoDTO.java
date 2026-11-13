package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para representar la configuración relevante de un tópico.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfiguracionTopicoDTO {
    private String retentionMs;
    private String cleanupPolicy;
    private String minInsyncReplicas;
    private String segmentBytes;
}
