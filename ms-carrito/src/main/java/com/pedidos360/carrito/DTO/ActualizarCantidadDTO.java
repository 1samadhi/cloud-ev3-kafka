package com.pedidos360.carrito.DTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** cantidad = 0 quita la línea. */
public record ActualizarCantidadDTO(
        @NotNull(message = "cantidad es obligatoria")
        @Min(value = 0, message = "cantidad no puede ser negativa")
        @Max(value = 99, message = "cantidad máxima por línea: 99")
        Integer cantidad) {
}
