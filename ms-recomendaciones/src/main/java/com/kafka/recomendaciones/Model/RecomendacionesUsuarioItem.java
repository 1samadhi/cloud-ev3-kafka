package com.kafka.recomendaciones.Model;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.util.List;

/**
 * Item de RecomendacionesUsuario: el ultimo top 5 calculado de un usuario.
 *
 * Un item por usuario con la lista completa embebida: se lee y se escribe
 * siempre entero, asi que partirlo en filas (como la tabla MySQL vieja) solo
 * agregaria lecturas y la ventana en que un lector ve un top a medio escribir.
 */
@Data
@NoArgsConstructor
@DynamoDbBean
public class RecomendacionesUsuarioItem {

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String usuarioId;

    private List<Recomendacion> recomendaciones;

    /** Epoch millis del calculo. */
    private Long calculadoEn;

    @Data
    @NoArgsConstructor
    @DynamoDbBean
    public static class Recomendacion {
        private Integer rankPosition;
        private Double score;
        private String itemId;
        private String nombre;
        private String categoria;
        private String subcategoria;
        private String marca;
        private Double precio;
        private Double calificacion;
        private String imagenUrl;
        private String motivo;
        private Integer stock;
    }
}
