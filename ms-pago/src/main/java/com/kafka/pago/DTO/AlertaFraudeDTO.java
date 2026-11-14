package com.kafka.pago.DTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO que representa una alerta de fraude individual para un cliente bloqueado.
 * Incluye información del bloqueo y minutos restantes calculados en tiempo de respuesta.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertaFraudeDTO {

    private String idCliente;
    private int cantidadIntentos;
    
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime bloqueadoHasta;
    
    private long minutosRestantes;
    
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime actualizadoEn;
}
