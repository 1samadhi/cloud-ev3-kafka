package com.kafka.admin.DTO;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO para representar un tópico en la lista de tópicos.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopicoDTO {
    private String nombre;
    private int particiones;
    private int replicas;
    private String isr;
}
