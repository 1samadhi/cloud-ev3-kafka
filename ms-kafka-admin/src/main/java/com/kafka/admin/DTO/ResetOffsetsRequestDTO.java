package com.kafka.admin.DTO;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Cuerpo de POST /api/consumer-groups/{id}/offsets/reset.
 *
 * "particiones" vacío o ausente = todas las del tópico.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResetOffsetsRequestDTO {

    @NotBlank(message = "Debe indicar el tópico")
    private String topico;

    @NotNull(message = "Debe indicar la estrategia: EARLIEST, LATEST, TIMESTAMP u OFFSET")
    private EstrategiaReset estrategia;

    /** Epoch ms para TIMESTAMP, offset para OFFSET. */
    private Long valor;

    private List<Integer> particiones;

    /*
     * Regla cruzada entre campos: se valida acá para que el 400 salga antes de
     * tocar el clúster. El servicio la vuelve a comprobar porque no debe
     * depender de que lo llame un controller.
     */
    @JsonIgnore
    @AssertTrue(message = "'valor' es obligatorio (>= 0) con TIMESTAMP u OFFSET y no se admite con EARLIEST o LATEST")
    public boolean isValorCoherente() {
        if (estrategia == null) {
            return true; // ya lo reporta @NotNull
        }
        boolean requiereValor = estrategia == EstrategiaReset.TIMESTAMP || estrategia == EstrategiaReset.OFFSET;
        return requiereValor ? valor != null && valor >= 0 : valor == null;
    }
}
