package com.pedidos360.productos.DTO;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Evento de interacción → tópico {@code interacciones-user} (contrato §2.3).
 * Copia local del DTO: no hay librería compartida entre servicios.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventoInteraccionDTO {

    @NotBlank(message = "idEvento es obligatorio")
    private String idEvento;

    /** También es la key del mensaje: todos los eventos de un usuario caen en la misma partición, en orden. */
    @NotBlank(message = "usuarioId es obligatorio")
    private String usuarioId;

    @NotNull(message = "tipoEvento es obligatorio")
    private TipoEvento tipoEvento;

    private String productoId;
    private String categoria;
    private String subcategoria;
    private String marca;

    @NotBlank(message = "origen es obligatorio")
    private String origen;

    private Map<String, String> detalle;

    @Positive(message = "timestamp debe ser epoch millis positivo")
    private long timestamp;

    /** productoId es obligatorio salvo en APLICAR_CUPON y GESTIONAR_ENVIO (§2.3). */
    @JsonIgnore
    @AssertTrue(message = "productoId es obligatorio para este tipoEvento")
    public boolean isProductoIdValidoParaTipo() {
        if (tipoEvento == null || tipoEvento == TipoEvento.APLICAR_CUPON || tipoEvento == TipoEvento.GESTIONAR_ENVIO) {
            return true;
        }
        return productoId != null && !productoId.isBlank();
    }
}
