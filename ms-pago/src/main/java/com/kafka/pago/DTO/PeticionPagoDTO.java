package com.kafka.pago.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class PeticionPagoDTO {
    @NotBlank(message = "clientId no puede estar vacío")
    private String clientId;

    @Positive(message = "amount debe ser mayor a 0")
    private Long amount;

    @NotBlank(message = "transactionId no puede estar vacío")
    private String transactionId;
}
