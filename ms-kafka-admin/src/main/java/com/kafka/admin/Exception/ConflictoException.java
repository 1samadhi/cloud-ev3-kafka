package com.kafka.admin.Exception;

import com.kafka.admin.Model.KafkaAdminException;

/**
 * La operación es válida pero el estado actual del clúster la impide: el
 * tópico ya existe, o el consumer group tiene miembros activos (Kafka sólo
 * deja mover offsets o borrar un grupo vacío). Se responde 409 porque el
 * cliente puede reintentar cuando cambie el estado, a diferencia de un 400.
 */
public class ConflictoException extends KafkaAdminException {
    public ConflictoException(String mensaje) {
        super(mensaje);
    }

    public ConflictoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
