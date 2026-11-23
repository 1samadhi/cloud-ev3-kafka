package com.pedidos360.carrito.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mensaje de pagos-resultado que publica ms-pago (§2.3 / §4). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResultadoPagoDTO {

    /** buyOrder de Webpay = id del pedido ("P-<n>"); también es la key del mensaje. */
    @NotBlank(message = "idOrden es obligatorio")
    private String idOrden;

    /** sessionId de Webpay = usuarioId. Opcional: sólo se usa para detectar inconsistencias. */
    private String idCliente;

    @NotNull(message = "estado es obligatorio")
    private EstadoPago estado;

    @PositiveOrZero(message = "monto no puede ser negativo")
    private Long monto;

    private String codigoAutorizacion;
    private String ultimos4;
    private Pasarela pasarela;
    private long timestamp;
}
