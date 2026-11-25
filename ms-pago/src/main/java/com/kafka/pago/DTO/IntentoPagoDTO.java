package com.kafka.pago.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Evento de payment-attempts (contrato §2.3). La key del mensaje es
 * huellaTarjeta: así todos los intentos de una misma tarjeta caen en la misma
 * partición y llegan en orden, que es lo que la ventana de 10 s necesita.
 *
 * Las validaciones las aplica el consumidor antes de delegar en el servicio:
 * un evento que no las cumple va directo al DLT sin reintentos.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentoPagoDTO {

    @NotBlank(message = "idIntento es obligatorio")
    private String idIntento;

    @NotBlank(message = "idOrden es obligatorio")
    private String idOrden;

    @NotBlank(message = "idCliente es obligatorio")
    private String idCliente;

    /** SHA-256 en hex: 64 caracteres. Nunca el número de tarjeta. */
    @NotBlank(message = "huellaTarjeta es obligatoria")
    @Pattern(regexp = "^[0-9a-f]{64}$", message = "huellaTarjeta debe ser un SHA-256 en hex")
    private String huellaTarjeta;

    @Pattern(regexp = "^\\d{4}$", message = "ultimos4 debe tener 4 dígitos")
    private String ultimos4;

    @NotNull(message = "monto es obligatorio")
    @Positive(message = "monto debe ser mayor a 0")
    private Long monto;

    @NotBlank(message = "moneda es obligatoria")
    private String moneda;

    @NotNull(message = "pasarela es obligatoria")
    private TipoPasarela pasarela;

    /** Epoch millis del intento: es el instante que cuenta la ventana. */
    @Positive(message = "timestamp debe ser epoch millis positivo")
    private long timestamp;
}
