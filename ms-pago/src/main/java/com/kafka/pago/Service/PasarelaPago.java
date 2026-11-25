package com.kafka.pago.Service;

import com.kafka.pago.DTO.TipoPasarela;

/**
 * Contrato común de las pasarelas. ServicioPagos no sabe si cobra contra
 * Transbank o contra el simulador: elige la implementación por tipo().
 */
public interface PasarelaPago {

    TipoPasarela tipo();

    /**
     * Inicia el cobro. Una pasarela con formulario externo (Webpay) devuelve
     * una Redireccion; una que decide en el acto (Simulada) devuelve una Decision.
     */
    ResultadoInicioPago iniciar(SolicitudCobro solicitud);

    /** Qué puede devolver una pasarela al iniciar. Sellado: el switch del servicio es exhaustivo. */
    sealed interface ResultadoInicioPago permits Redireccion, Decision {
    }

    record Redireccion(String url, String token) implements ResultadoInicioPago {
    }

    record Decision(boolean aprobado, String codigoAutorizacion) implements ResultadoInicioPago {
    }

    /**
     * Datos de un cobro. numeroTarjeta sólo existe con SIMULADA y toString()
     * está sobrescrito para que nunca aparezca en un log.
     */
    record SolicitudCobro(String idOrden, String idCliente, long monto, String returnUrl, String numeroTarjeta) {
        @Override
        public String toString() {
            return "SolicitudCobro[idOrden=" + idOrden + ", idCliente=" + idCliente + ", monto=" + monto
                    + ", returnUrl=" + returnUrl + ", tarjeta=" + (numeroTarjeta == null ? "-"
                    : "****" + HuellaTarjeta.ultimos4(numeroTarjeta)) + "]";
        }
    }
}
