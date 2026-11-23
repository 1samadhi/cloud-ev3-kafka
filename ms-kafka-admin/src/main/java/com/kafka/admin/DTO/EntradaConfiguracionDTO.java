package com.kafka.admin.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Una entrada de configuración (de tópico o de broker) tal como la reporta
 * describeConfigs.
 *
 * Se expone "porDefecto" además del "origen" crudo de Kafka porque lo que la
 * consola necesita mostrar es si alguien la sobrescribió en ESTE recurso; el
 * origen (DYNAMIC_TOPIC_CONFIG, STATIC_BROKER_CONFIG, DEFAULT_CONFIG...) queda
 * para quien quiera saber de dónde se hereda.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntradaConfiguracionDTO {
    private String nombre;
    /** null cuando la entrada es sensible: Kafka no devuelve secretos. */
    private String valor;
    private boolean porDefecto;
    private String origen;
    private boolean sensible;
    private boolean soloLectura;
    /** true si está en la lista blanca de PUT /api/topicos/{nombre}/configuracion. */
    private boolean editable;
}
