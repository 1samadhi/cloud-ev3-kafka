package com.kafka.recomendaciones.Model;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.util.Map;

/**
 * Item de la tabla HistorialEventos: cada evento consumido por grupo-recomendaciones.
 *
 * PK usuarioId + SK "instante#idEvento". La SK lleva el instante con ceros a la
 * izquierda (13 digitos) para que el orden lexicografico de DynamoDB coincida con
 * el cronologico: asi "los ultimos 100 eventos" es un Query descendente con
 * limite, sin Scan. El idEvento al final hace la clave unica y el reproceso
 * idempotente: el mismo evento reescribe el mismo item en vez de duplicarlo.
 */
@Data
@NoArgsConstructor
@DynamoDbBean
public class EventoHistorialItem {

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String usuarioId;

    @Getter(onMethod_ = @DynamoDbSortKey)
    private String instanteEvento;

    private String idEvento;
    private String tipoEvento;
    private String productoId;
    private String categoria;
    private String subcategoria;
    private String marca;
    private String origen;
    private Map<String, String> detalle;
    private Long timestamp;

    public static String claveOrden(long timestamp, String idEvento) {
        return String.format("%013d#%s", timestamp, idEvento);
    }
}
