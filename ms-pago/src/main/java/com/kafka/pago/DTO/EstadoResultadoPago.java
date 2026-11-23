package com.kafka.pago.DTO;

/** Estado final de un pago tal como lo publica ms-pago en pagos-resultado. */
public enum EstadoResultadoPago {
    AUTORIZADO,
    RECHAZADO,
    ANULADO,
    ERROR
}
