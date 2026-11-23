package com.pedidos360.rabbitadmin.Component;

import com.pedidos360.rabbitadmin.Config.ProteccionRecursosProperties;
import com.pedidos360.rabbitadmin.Exception.RecursoProtegidoException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Única fuente de verdad sobre qué NO se borra sin ?forzar=true.
 *
 * Protegidos:
 *  - El exchange por defecto (""): lo usa cualquier publicación directa a una cola.
 *  - Todo lo que empiece con "amq.": prefijo reservado por RabbitMQ
 *    (amq.direct, amq.topic, amq.fanout, amq.headers, amq.match, amq.rabbitmq.trace
 *    y las colas amq.gen-* con nombre generado por el servidor).
 *  - La topología de notificaciones de Pedidos360 (configurable).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProteccionRecursos {

    /**
     * Fijo en el código y no en el yaml: no es una política del proyecto sino
     * una regla del broker, y nadie debería poder "desprotegerlo" por config.
     */
    public static final String PREFIJO_RESERVADO = "amq.";

    private final ProteccionRecursosProperties props;

    public boolean esProtegido(String nombre) {
        if (nombre == null || nombre.isEmpty()) {
            return true;
        }
        if (nombre.startsWith(PREFIJO_RESERVADO)) {
            return true;
        }
        return props.getSistema().contains(nombre);
    }

    /**
     * Un binding es protegido si toca la topología del sistema: quitar
     * pedidos360.notificaciones → notificaciones.cliente corta las notificaciones
     * aunque las dos puntas sigan existiendo. Un binding sobre amq.topic creado
     * a mano NO se protege: borrarlo no afecta al broker ni a los servicios.
     */
    public boolean esBindingProtegido(String origen, String destino) {
        return props.getSistema().contains(origen) || props.getSistema().contains(destino);
    }

    public String motivo(String nombre) {
        if (nombre == null || nombre.isEmpty()) {
            return "es el exchange por defecto de RabbitMQ";
        }
        if (nombre.startsWith(PREFIJO_RESERVADO)) {
            return "el prefijo 'amq.' está reservado por RabbitMQ";
        }
        return "es parte de la topología de notificaciones de Pedidos360 (ms-carrito y ms-notificaciones dependen de él)";
    }

    /** Lanza 403 si el recurso es protegido y no se pidió forzar; deja un WARN si se fuerza. */
    public void verificarBorrado(String recurso, String nombre, boolean forzar) {
        if (!esProtegido(nombre)) {
            return;
        }
        if (!forzar) {
            throw new RecursoProtegidoException("No se borra " + recurso + " '" + nombre + "': " + motivo(nombre)
                    + ". Si de verdad hay que hacerlo, repetir con ?forzar=true");
        }
        log.warn("Borrado FORZADO de {} protegido '{}' ({})", recurso, nombre, motivo(nombre));
    }

    public void verificarBorradoBinding(String origen, String destino, String routingKey, boolean forzar) {
        if (!esBindingProtegido(origen, destino)) {
            return;
        }
        String descripcion = "'" + origen + "' -> '" + destino + "' [" + routingKey + "]";
        if (!forzar) {
            throw new RecursoProtegidoException("No se borra el binding " + descripcion
                    + ": es parte de la topología de notificaciones de Pedidos360. Repetir con ?forzar=true");
        }
        log.warn("Borrado FORZADO del binding protegido {}", descripcion);
    }
}
