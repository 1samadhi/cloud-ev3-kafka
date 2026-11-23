package com.kafka.pago.DTO;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * Cuerpo de POST /api/pagos/iniciar.
 *
 * clientId, amount y transactionId se conservan con esos nombres en inglés
 * porque la consola React ya los envía así. Los campos nuevos son opcionales
 * para no romperla: sin pasarela se asume WEBPAY y sin origen, CONSOLA.
 */
@Data
public class PeticionPagoDTO {

    @NotBlank(message = "clientId no puede estar vacío")
    private String clientId;

    @NotNull(message = "amount es obligatorio")
    @Positive(message = "amount debe ser mayor a 0")
    private Long amount;

    @NotBlank(message = "transactionId no puede estar vacío")
    private String transactionId;

    private TipoPasarela pasarela;

    /** Obligatoria sólo con SIMULADA (ver isTarjetaPresenteSiSimulada). */
    @Valid
    private TarjetaDTO tarjeta;

    private OrigenPago origen;

    /** Un null explícito en el JSON también cae en el valor por defecto. */
    public TipoPasarela getPasarela() {
        return pasarela == null ? TipoPasarela.WEBPAY : pasarela;
    }

    public OrigenPago getOrigen() {
        return origen == null ? OrigenPago.CONSOLA : origen;
    }

    /**
     * Con SIMULADA la tarjeta es la clave del antifraude: sin ella no hay
     * huella que contar. Con WEBPAY se ignora aunque venga, porque Transbank
     * captura la tarjeta en su propio formulario.
     */
    @JsonIgnore
    @AssertTrue(message = "tarjeta.numero es obligatorio cuando la pasarela es SIMULADA")
    public boolean isTarjetaPresenteSiSimulada() {
        return getPasarela() != TipoPasarela.SIMULADA
                || (tarjeta != null && tarjeta.getNumero() != null && !tarjeta.getNumero().isBlank());
    }
}
