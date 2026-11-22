package com.kafka.recomendaciones.DTO;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Evento de interacciones-user (§2.3). Copia local del contrato: no hay libreria
 * compartida, cada servicio deserializa a su propia clase (por eso el productor
 * no manda cabeceras de tipo).
 *
 * ignoreUnknown: si otro servicio agrega un campo, este consumidor no debe caer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventoInteraccionDTO {

    @NotBlank(message = "idEvento es obligatorio")
    private String idEvento;

    /** Tambien es la key del mensaje: ordena los eventos de un usuario en una particion. */
    @NotBlank(message = "usuarioId es obligatorio")
    private String usuarioId;

    @NotNull(message = "tipoEvento es obligatorio")
    private TipoEvento tipoEvento;

    private String productoId;
    private String categoria;
    private String subcategoria;
    private String marca;
    private String origen;
    private Map<String, String> detalle;

    @Positive(message = "timestamp debe ser epoch millis positivo")
    private long timestamp;

    /**
     * productoId es obligatorio salvo para APLICAR_CUPON y GESTIONAR_ENVIO. Es una
     * regla entre dos campos, por eso va como @AssertTrue y no en el campo.
     * JsonIgnore: es una validacion, no un dato del mensaje.
     */
    @JsonIgnore
    @AssertTrue(message = "productoId es obligatorio salvo en APLICAR_CUPON y GESTIONAR_ENVIO")
    public boolean isProductoCoherente() {
        return tipoEvento == null || !tipoEvento.requiereProducto()
                || (productoId != null && !productoId.isBlank());
    }
}
