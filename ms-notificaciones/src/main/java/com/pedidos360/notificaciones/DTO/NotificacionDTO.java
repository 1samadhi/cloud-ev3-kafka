package com.pedidos360.notificaciones.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Mensaje que llega por RabbitMQ (PLAN-INTEGRACION-EV3 §9). Copia propia del
 * contrato: no hay librería compartida con ms-carrito; lo que los une es el JSON.
 *
 * Las validaciones son las que deciden si un mensaje tiene arreglo: sin
 * usuarioId no hay a quién entregarle la notificación, y reintentar no lo
 * cambia -> directo a la DLQ.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificacionDTO {

    /** UUID del productor: también es la clave de idempotencia (at-least-once). */
    @NotBlank(message = "idNotificacion es obligatorio")
    @Size(max = 64, message = "idNotificacion no puede superar 64 caracteres")
    private String idNotificacion;

    @NotBlank(message = "usuarioId es obligatorio")
    @Size(max = 128, message = "usuarioId no puede superar 128 caracteres")
    private String usuarioId;

    /** Igual a la routing key (carrito.producto-agregado, pedido.creado, ...). */
    @NotBlank(message = "tipo es obligatorio")
    private String tipo;

    @NotBlank(message = "titulo es obligatorio")
    @Size(max = 200, message = "titulo no puede superar 200 caracteres")
    private String titulo;

    @NotBlank(message = "mensaje es obligatorio")
    @Size(max = 1000, message = "mensaje no puede superar 1000 caracteres")
    private String mensaje;

    /** Nulo para notificaciones que no son de un pedido (carrito). */
    private String idPedido;

    private Map<String, Object> datos;

    /** Epoch millis del productor; si falta se usa la hora de recepción. */
    private Long timestamp;
}
