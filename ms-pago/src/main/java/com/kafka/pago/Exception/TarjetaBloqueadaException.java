package com.kafka.pago.Exception;

import java.time.LocalDateTime;

import lombok.Getter;

/** La huella de la tarjeta (o de la cuenta, con Webpay) tiene un bloqueo vigente → 429. */
@Getter
public class TarjetaBloqueadaException extends RuntimeException {

    private final LocalDateTime bloqueadoHasta;
    private final int intentos;
    private final long minutosRestantes;

    public TarjetaBloqueadaException(LocalDateTime bloqueadoHasta, int intentos, long minutosRestantes) {
        super("Huella bloqueada hasta " + bloqueadoHasta);
        this.bloqueadoHasta = bloqueadoHasta;
        this.intentos = intentos;
        this.minutosRestantes = minutosRestantes;
    }
}
