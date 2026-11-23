package com.kafka.admin.DTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cuerpo de POST /api/topicos/{nombre}/particiones: la cantidad TOTAL deseada,
 * no cuántas agregar, porque así lo pide createPartitions (increaseTo) y la
 * operación queda idempotente frente a un doble clic.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AumentarParticionesRequestDTO {

    @NotNull(message = "Debe indicar la cantidad total de particiones")
    @Min(value = 1, message = "Las particiones deben ser >= 1")
    // Tope de seguridad del proyecto: cada partición son archivos abiertos y
    // réplicas en los 3 brokers; un 10000 por error tumba un clúster de laboratorio.
    @Max(value = 1000, message = "Las particiones no pueden superar 1000")
    private Integer particiones;
}
