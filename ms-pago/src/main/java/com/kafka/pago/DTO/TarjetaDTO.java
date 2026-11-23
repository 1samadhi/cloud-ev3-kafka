package com.kafka.pago.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Datos de tarjeta que sólo viajan con la pasarela SIMULADA.
 *
 * El número se usa en memoria para calcular la huella y los últimos 4 dígitos,
 * y se descarta: nunca se persiste, nunca se publica en Kafka y nunca se
 * loguea. Por eso toString() está escrito a mano (el de Lombok lo imprimiría
 * completo en cualquier log que concatene el DTO).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TarjetaDTO {

    /**
     * Entre 13 y 19 dígitos (rango ISO/IEC 7812), admitiendo un espacio o guion
     * entre dígitos porque así lo escriben las personas.
     */
    @NotBlank(message = "tarjeta.numero es obligatorio")
    @Pattern(regexp = "^(?:\\d[ -]?){12,18}\\d$",
             message = "tarjeta.numero debe tener entre 13 y 19 dígitos")
    private String numero;

    @Override
    public String toString() {
        String digitos = numero == null ? "" : numero.replaceAll("[^0-9]", "");
        String fin = digitos.length() >= 4 ? digitos.substring(digitos.length() - 4) : "????";
        return "TarjetaDTO[****" + fin + "]";
    }
}
