package com.kafka.pago.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Evento de pagos-resultado (contrato §2.3), key = idOrden. Lo consume
 * ms-carrito para pasar el pedido a PAGADO o PAGO_RECHAZADO: sin este evento
 * nadie fuera de ms-pago se enteraba de la venta.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultadoPagoDTO {
    private String idOrden;
    private String idCliente;
    private EstadoResultadoPago estado;
    private Long monto;
    private String codigoAutorizacion;
    private String ultimos4;
    private TipoPasarela pasarela;
    private long timestamp;
}
