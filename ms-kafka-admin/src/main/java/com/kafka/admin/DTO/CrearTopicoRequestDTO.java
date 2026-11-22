package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO para la solicitud de creación de un tópico.
 *
 * "configs" es opcional y se agregó sin tocar los campos existentes (la consola
 * envía sólo nombre/particiones/replicas): admite cualquier clave de la misma
 * lista blanca que el PUT de configuración, para poder crear desde la API un
 * tópico como los de infra/kafka/crear-topicos.sh.
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
    private Map<String, String> configs;

    /** Constructor previo a "configs", conservado para no romper a quien ya lo usa. */
    public CrearTopicoRequestDTO(String nombre, int particiones, int replicas,
                                 Long retentionMs, String cleanupPolicy, Integer minInsyncReplicas) {
        this(nombre, particiones, replicas, retentionMs, cleanupPolicy, minInsyncReplicas, null);
    }
}
