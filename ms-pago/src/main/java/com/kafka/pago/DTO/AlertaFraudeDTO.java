package com.kafka.pago.DTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Un bloqueo vigente tal como lo muestra la consola (GET /api/alertas).
 *
 * Los cinco primeros campos y su formato se mantienen idénticos porque
 * frontend/src/lib/types.ts los lee (AlertaFraude). huellaTarjeta y ultimos4
 * se agregan al final: un cliente viejo los ignora.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertaFraudeDTO {

    /** Último cliente que usó la tarjeta bloqueada (la consola filtra por él). */
    private String idCliente;
    private int cantidadIntentos;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime bloqueadoHasta;

    private long minutosRestantes;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime actualizadoEn;

    /** Sólo los primeros 12 hex: alcanza para distinguir tarjetas en pantalla. */
    private String huellaTarjeta;

    /** Null con WEBPAY: Transbank no entrega la tarjeta antes del pago. */
    private String ultimos4;
}
