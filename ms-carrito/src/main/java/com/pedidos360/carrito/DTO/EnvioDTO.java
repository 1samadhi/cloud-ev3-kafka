package com.pedidos360.carrito.DTO;

import com.pedidos360.carrito.Model.MetodoEnvio;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** ENVIO exige direccion + comuna; RETIRO exige sucursal (se valida en el servicio, depende del método). */
public record EnvioDTO(
        @NotNull(message = "metodo es obligatorio (ENVIO o RETIRO)")
        MetodoEnvio metodo,

        @Size(max = 200, message = "direccion admite hasta 200 caracteres")
        String direccion,

        @Size(max = 80, message = "comuna admite hasta 80 caracteres")
        String comuna,

        @Size(max = 120, message = "sucursal admite hasta 120 caracteres")
        String sucursal) {
}
