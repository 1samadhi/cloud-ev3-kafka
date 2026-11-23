package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Configuración completa de un tópico o de un broker.
 *
 * "sobrescritas" repite, en forma de mapa, sólo las entradas que NO están por
 * defecto: es lo que hay que mirar para justificar la configuración de cada
 * flujo (criterio 7) sin leer las ~30 entradas.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfiguracionRecursoDTO {
    /** Nombre del tópico o id del broker. */
    private String recurso;
    /** TOPIC o BROKER. */
    private String tipo;
    private List<EntradaConfiguracionDTO> entradas;
    private Map<String, String> sobrescritas;
}
