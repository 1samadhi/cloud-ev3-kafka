package com.pedidos360.productos.DTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemReservaDTO {

    @NotBlank(message = "productoId es obligatorio")
    @Size(max = 64, message = "productoId admite como máximo 64 caracteres")
    private String productoId;

    // Tope por ítem: evita que un cliente bugueado (o malicioso) vacíe el stock completo
    // de un producto en una sola petición.
    @NotNull(message = "cantidad es obligatoria")
    @Min(value = 1, message = "cantidad debe ser al menos 1")
    @Max(value = 1000, message = "cantidad no puede superar 1000 por ítem")
    private Integer cantidad;
}
