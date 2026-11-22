package com.kafka.admin.Model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Cuerpo de toda respuesta de error.
 *
 * Forma del contrato: { error, detalle, timestamp }. Se conservan "mensaje" y
 * "codigo" porque la consola React ya los lee (api.ts busca "message",
 * "mensaje" y luego "error"); quitarlos dejaría a la consola mostrando sólo el
 * código HTTP.
 */
@Data
@NoArgsConstructor
public class ErrorResponseDTO {
    /** Categoría corta: "Solicitud inválida", "No encontrado", "Conflicto"... */
    private String error;
    /** Explicación concreta de qué falló y cómo arreglarlo. */
    private String detalle;
    /** Igual a "detalle"; se mantiene por compatibilidad con la consola. */
    private String mensaje;
    private int codigo;
    private String timestamp;

    public ErrorResponseDTO(String error, String detalle, int codigo) {
        this.error = error;
        this.detalle = detalle;
        this.mensaje = detalle;
        this.codigo = codigo;
        this.timestamp = Instant.now().toString();
    }

    /** Firma original (mensaje, codigo), conservada por compatibilidad. */
    public ErrorResponseDTO(String mensaje, int codigo) {
        this("Error", mensaje, codigo);
    }
}
