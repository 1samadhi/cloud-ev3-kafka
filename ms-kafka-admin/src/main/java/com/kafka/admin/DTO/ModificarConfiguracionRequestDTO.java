package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Cuerpo de PUT /api/topicos/{nombre}/configuracion.
 *
 * Se traduce a incrementalAlterConfigs: "establecer" son operaciones SET y
 * "restablecer" son DELETE (en Kafka, borrar el override de un tópico lo
 * devuelve al valor heredado del broker). Se usa la API incremental y no la
 * vieja alterConfigs porque ésta reemplaza la configuración entera: cambiar
 * retention.ms borraría en silencio el min.insync.replicas del tópico.
 *
 * Las claves se validan contra la lista blanca en el servicio, no acá, porque
 * la regla min.insync.replicas <= RF necesita consultar el tópico.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModificarConfiguracionRequestDTO {
    private Map<String, String> establecer;
    private List<String> restablecer;
}
