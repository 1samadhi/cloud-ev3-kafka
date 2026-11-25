package com.kafka.recomendaciones.Exception;

/**
 * Datos que no cumplen el contrato (DTO de Kafka invalido, accion desconocida,
 * usuario faltante). Se marca como NO reintentable en el DefaultErrorHandler:
 * reintentar un payload malo no lo arregla, va directo al DLT. En HTTP es un 400.
 */
public class PayloadInvalidoException extends RuntimeException {

    public PayloadInvalidoException(String mensaje) {
        super(mensaje);
    }
}
