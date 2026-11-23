package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Respuesta de POST /api/topicos/{nombre}/particiones. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResultadoParticionesDTO {
    private String topico;
    private int particionesAnteriores;
    private int particionesActuales;
    /** Consecuencias que el operador debe conocer (reparto de keys, DLT). */
    private List<String> advertencias;
}
