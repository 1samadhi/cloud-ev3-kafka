package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Una partición asignada a un miembro de un consumer group. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AsignacionParticionDTO {
    private String topico;
    private int particion;
}
