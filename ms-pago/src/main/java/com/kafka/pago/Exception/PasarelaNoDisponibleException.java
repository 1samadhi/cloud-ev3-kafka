package com.kafka.pago.Exception;

/** La pasarela externa (Transbank) falló o no respondió. Se traduce a 502. */
public class PasarelaNoDisponibleException extends RuntimeException {
    public PasarelaNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
