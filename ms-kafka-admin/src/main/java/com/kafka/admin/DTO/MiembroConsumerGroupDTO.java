package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Un consumidor vivo dentro de un grupo y las particiones que tiene. Es lo que
 * demuestra el reparto (criterio 8): con concurrency 3 sobre 6 particiones se
 * ven 3 miembros con 2 particiones cada uno.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MiembroConsumerGroupDTO {
    private String idMiembro;
    private String idCliente;
    private String host;
    /** Sólo con membresía estática (group.instance.id); si no, null. */
    private String idInstancia;
    private List<AsignacionParticionDTO> asignaciones;
}
