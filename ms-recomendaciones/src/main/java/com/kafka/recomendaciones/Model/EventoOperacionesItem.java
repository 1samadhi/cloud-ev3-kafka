package com.kafka.recomendaciones.Model;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.util.List;
import java.util.Map;

/**
 * Item de EventosOperaciones: la tabla con Streams que dispara la Lambda
 * Procesador (lambda-telemetria, doc 07).
 *
 * Las claves siguen a infra/terraform-telemetria/dynamodb.tf, que es la tabla que
 * existe de verdad: PK "tipo" (S) + SK "ocurridoEn" (S, ISO-8601) y TTL en
 * "expiraEn" (epoch SEGUNDOS, lo que exige el TTL de DynamoDB). El contrato §5
 * decia PK "id"; con esa clave el item no entraria en la tabla desplegada, asi
 * que se respeta el Terraform y "id" viaja como atributo comun.
 *
 * La Lambda desenvuelve el item tal cual y lo difunde; la consola lo reconoce
 * por tipo === "metricas" o por la presencia de productosMasVistos, por eso ambos
 * van con esos nombres exactos.
 */
@Data
@NoArgsConstructor
@DynamoDbBean
public class EventoOperacionesItem {

    /** Valor de "tipo" para los snapshots de metricas (ms-pago escribe "alerta"). */
    public static final String TIPO_METRICAS = "metricas";

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String tipo;

    @Getter(onMethod_ = @DynamoDbSortKey)
    private String ocurridoEn;

    private String id;
    private String origen;
    private Long expiraEn;
    private Long timestamp;
    private Long interaccionesPorMinuto;
    private Long usuariosActivos;
    private Long totalInteracciones;
    private List<PuntoSerie> serieMinutos;
    private List<TopProducto> topProductos;
    private List<ProductoVisto> productosMasVistos;
    private Embudo embudo;
    private Map<String, Long> porTipo;

    @Data
    @NoArgsConstructor
    @DynamoDbBean
    public static class PuntoSerie {
        private Long inicio;
        private Long interacciones;
    }

    @Data
    @NoArgsConstructor
    @DynamoDbBean
    public static class TopProducto {
        private String productoId;
        private String nombre;
        private String categoria;
        private Long vistas;
    }

    @Data
    @NoArgsConstructor
    @DynamoDbBean
    public static class ProductoVisto {
        private String itemId;
        private String nombre;
        private String categoria;
        private Long vistas;
    }

    @Data
    @NoArgsConstructor
    @DynamoDbBean
    public static class Embudo {
        private Long vistas;
        private Long carritos;
        private Long compras;
    }
}
