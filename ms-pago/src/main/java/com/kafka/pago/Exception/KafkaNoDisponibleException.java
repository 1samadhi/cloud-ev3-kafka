package com.kafka.pago.Exception;

/**
 * El broker no confirmó la publicación del intento a tiempo. En ms-pago esto
 * corta el pago con 503: publicar el intento ES el control antifraude, y
 * cobrar sin registrarlo dejaría pasar justo la ráfaga que hay que detectar.
 */
public class KafkaNoDisponibleException extends RuntimeException {
    public KafkaNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
