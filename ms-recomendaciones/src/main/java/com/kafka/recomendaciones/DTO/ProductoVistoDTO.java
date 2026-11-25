package com.kafka.recomendaciones.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Forma HISTORICA de un producto mas visto ({itemId, nombre, categoria, vistas}).
 * Se mantiene dentro del snapshot como "productosMasVistos" porque la pantalla de
 * Operaciones de la consola la lee asi, tanto del GET como del WebSocket.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductoVistoDTO {
    private String itemId;
    private String nombre;
    private String categoria;
    private long vistas;
}
