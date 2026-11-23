package com.kafka.admin.Exception;

import com.kafka.admin.Model.KafkaAdminException;

/** Tópico, consumer group o broker inexistente. Se responde 404. */
public class RecursoNoEncontradoException extends KafkaAdminException {
    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }

    public RecursoNoEncontradoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
