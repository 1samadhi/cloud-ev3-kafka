package com.pedidos360.productos.Service;

import java.util.List;

import com.pedidos360.productos.DTO.ProductoCatalogoDTO;

/**
 * Evento de aplicación (Spring, no Kafka) que emite el servicio al cambiar stock o precio.
 * Lleva los snapshots ya construidos dentro de la transacción; el publicador los manda a
 * Kafka sólo DESPUÉS del commit (ver PublicadorCambiosCatalogo).
 */
public record CambioCatalogoEvento(String motivo, List<ProductoCatalogoDTO> snapshots) {
}
