package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO para representar el detalle completo de un tópico,
 * incluyendo configuración y detalle por partición.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopicoDetalleDTO {
    private String nombre;
    private int particiones;
    private int replicas;
    private String isr;
    private ConfiguracionTopicoDTO configuracion;
    private List<ParticionDetalleDTO> particionesDetalle;
}
