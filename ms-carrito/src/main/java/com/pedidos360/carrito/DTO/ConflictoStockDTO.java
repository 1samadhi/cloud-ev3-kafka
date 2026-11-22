package com.pedidos360.carrito.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Cuerpo del 409 de ms-productos (su ErrorDTO): { error, detalle, faltantes:[{productoId, disponible}], timestamp }.
 * faltantes viene vacío/nulo cuando el 409 no es por stock sino por el estado de la reserva
 * (ConflictoReservaException: p. ej. reservar de nuevo un idPedido ya liberado).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConflictoStockDTO(String error, String detalle, List<FaltanteDTO> faltantes) {
}
