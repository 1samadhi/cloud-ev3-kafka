package com.pedidos360.productos.DTO;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cuerpo de {@code POST /api/productos/stock/reservas} y {@code /liberaciones}.
 * idPedido es la clave de idempotencia: repetir la misma petición no descuenta ni
 * devuelve stock dos veces.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeticionReservaDTO {

    @NotBlank(message = "idPedido es obligatorio")
    @Size(max = 64, message = "idPedido admite como máximo 64 caracteres")
    @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "idPedido sólo admite letras, dígitos, punto, guion y guion bajo")
    private String idPedido;

    @NotEmpty(message = "items no puede estar vacío")
    @Size(max = 50, message = "items admite como máximo 50 líneas")
    @Valid
    private List<ItemReservaDTO> items;
}
