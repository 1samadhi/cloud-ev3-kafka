package com.pedidos360.carrito.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Evento de interacciones-user (§2.3). Copia local del contrato: no hay librería compartida
 * para que ningún servicio dependa del paquete Java de otro (el productor además publica sin
 * headers de tipo).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventoInteraccionDTO {
    private String idEvento;
    /** También es la key del mensaje: todos los eventos de un usuario caen en la misma partición, en orden. */
    private String usuarioId;
    private TipoEvento tipoEvento;
    /** Obligatorio salvo APLICAR_CUPON y GESTIONAR_ENVIO. */
    private String productoId;
    private String categoria;
    private String subcategoria;
    private String marca;
    private String origen;
    private Map<String, String> detalle;
    private long timestamp;
}
