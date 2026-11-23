package com.pedidos360.rabbitadmin.DTO;

import java.util.List;
import java.util.Map;

/**
 * Cola para la consola. mensajes = listos + sin confirmar; los tres pueden ser
 * null si el broker todavía no publicó estadísticas de una cola recién creada.
 * nodo = líder Raft en las quorum, nodo anfitrión en las classic.
 * protegida = no se borra sin ?forzar=true.
 */
public record ColaDTO(
        String nombre,
        String vhost,
        String tipo,
        boolean durable,
        boolean autoEliminar,
        boolean exclusiva,
        Long mensajes,
        Long mensajesListos,
        Long mensajesSinConfirmar,
        Integer consumidores,
        String estado,
        String nodo,
        List<String> miembros,
        List<String> miembrosEnLinea,
        Map<String, Object> argumentos,
        boolean protegida) {
}
