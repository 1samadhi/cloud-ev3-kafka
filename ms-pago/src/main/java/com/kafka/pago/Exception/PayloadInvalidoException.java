package com.kafka.pago.Exception;

/**
 * Un mensaje de Kafka que llegó bien formado como JSON pero no cumple el
 * contrato (campos vacíos, monto negativo, huella que no es SHA-256).
 * Está marcada como NO reintentable en el DefaultErrorHandler: reintentarla
 * daría el mismo resultado, así que va directo al DLT.
 */
public class PayloadInvalidoException extends RuntimeException {
    public PayloadInvalidoException(String mensaje) {
        super(mensaje);
    }
}
