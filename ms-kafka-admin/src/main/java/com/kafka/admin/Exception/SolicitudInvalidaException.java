package com.kafka.admin.Exception;

import com.kafka.admin.Model.KafkaAdminException;

/**
 * La petición viola una regla (lista blanca, tipo de valor, "sólo aumentar
 * particiones", RF mayor que los brokers...). Se responde 400.
 *
 * Hereda de KafkaAdminException para que el código que ya capturaba la
 * excepción genérica siga funcionando; el handler distingue por tipo y no por
 * el texto del mensaje, que era frágil (cambiar una palabra cambiaba el código
 * HTTP).
 */
public class SolicitudInvalidaException extends KafkaAdminException {
    public SolicitudInvalidaException(String mensaje) {
        super(mensaje);
    }

    public SolicitudInvalidaException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
