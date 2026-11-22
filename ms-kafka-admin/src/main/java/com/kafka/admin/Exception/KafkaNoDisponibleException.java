package com.kafka.admin.Exception;

import com.kafka.admin.Model.KafkaAdminException;

/**
 * El clúster no respondió a tiempo. Se responde 503 y no 500: el servicio
 * está sano, lo que falta es su dependencia, y la consola lo muestra distinto.
 */
public class KafkaNoDisponibleException extends KafkaAdminException {
    public KafkaNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
