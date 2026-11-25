package com.kafka.recomendaciones.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** Respuesta de POST /api/interacciones/simular: cuanto se publico y de que tipo. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResultadoSimulacionDTO {
    private int publicados;
    private List<String> usuarios;
    private Map<String, Integer> porTipo;
}
