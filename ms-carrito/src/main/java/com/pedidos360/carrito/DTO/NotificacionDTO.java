package com.pedidos360.carrito.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Mensaje RabbitMQ hacia ms-notificaciones (§9). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotificacionDTO {
    private String idNotificacion;
    private String usuarioId;
    /** Igual a la routing key (carrito.producto-agregado, pedido.creado, ...). */
    private String tipo;
    private String titulo;
    private String mensaje;
    private String idPedido;
    private Map<String, String> datos;
    private long timestamp;
}
