package com.pedidos360.carrito.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AplicarCuponDTO(
        @NotBlank(message = "codigo es obligatorio")
        @Size(max = 40, message = "codigo admite hasta 40 caracteres")
        String codigo) {
}
