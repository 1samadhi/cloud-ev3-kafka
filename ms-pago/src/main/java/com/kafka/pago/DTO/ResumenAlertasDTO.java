package com.kafka.pago.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Resumen para el panel de alertas. Forma idéntica a la anterior (la consola
 * la lee). totalClientesVigilados conserva el nombre por compatibilidad, pero
 * ahora cuenta huellas de tarjeta distintas con intentos en la última hora
 * (la retención de la tabla intento_tarjeta).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumenAlertasDTO {

    private int bloqueosActivos;
    private int totalClientesVigilados;
    private List<AlertaFraudeDTO> alertas;
}
