package com.pedidos360.carrito.DTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgregarItemDTO(
        @NotBlank(message = "productoId es obligatorio")
        @Size(max = 40, message = "productoId admite hasta 40 caracteres")
        String productoId,

        @NotNull(message = "cantidad es obligatoria")
        @Min(value = 1, message = "cantidad debe ser al menos 1")
        @Max(value = 99, message = "cantidad máxima por operación: 99")
        Integer cantidad) {
}
