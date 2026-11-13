package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO para representar un consumer group.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerGroupDTO {
    private String id;
    private String estado;
    private int cantidadMiembros;
    private List<String> topicos;
}
