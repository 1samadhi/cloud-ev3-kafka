package com.pedidos360.productos.DTO;

import java.util.List;

/**
 * Respuesta 200 de reservas y liberaciones.
 *
 * {@code resultado} hace explícito si la operación cambió stock o fue una repetición
 * idempotente: RESERVADA | YA_RESERVADA | LIBERADA | YA_LIBERADA | SIN_RESERVA.
 * {@code items} son las cantidades efectivamente reservadas/devueltas (las de la
 * reserva guardada, no necesariamente las del cuerpo recibido).
 */
public record RespuestaReservaDTO(String idPedido, String resultado, List<ItemReservaDTO> items, long timestamp) {
}
