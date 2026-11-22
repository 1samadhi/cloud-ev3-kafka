package com.pedidos360.productos.DTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Cuerpo de {@code PUT /api/productos/{id}/stock}: unidades que se SUMAN al stock (reponer). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeticionReposicionDTO {

    @NotNull(message = "cantidad es obligatoria")
    @Min(value = 1, message = "cantidad debe ser al menos 1")
    @Max(value = 10000, message = "cantidad no puede superar 10000 por reposición")
    private Integer cantidad;
}
