package com.kafka.pago.Service;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

import com.kafka.pago.DTO.TipoPasarela;

import lombok.extern.slf4j.Slf4j;

/**
 * Pasarela de demostración. Existe porque con Webpay el backend nunca ve la
 * tarjeta, y el enunciado pide contar "la misma tarjeta": con esta pasarela la
 * huella sale del número real.
 *
 * Regla documentada: APRUEBA todo, salvo las tarjetas que terminan en 0002,
 * que RECHAZA (permite mostrar el camino del rechazo sin otra configuración).
 */
@Slf4j
@Component
public class PasarelaSimulada implements PasarelaPago {

    public static final String SUFIJO_RECHAZO = "0002";

    private final SecureRandom aleatorio = new SecureRandom();

    @Override
    public TipoPasarela tipo() {
        return TipoPasarela.SIMULADA;
    }

    @Override
    public ResultadoInicioPago iniciar(SolicitudCobro solicitud) {
        String digitos = HuellaTarjeta.normalizar(solicitud.numeroTarjeta());
        if (digitos.isEmpty()) {
            throw new IllegalArgumentException("La pasarela SIMULADA necesita el número de tarjeta");
        }
        boolean aprobado = !digitos.endsWith(SUFIJO_RECHAZO);
        String codigo = aprobado ? String.format("%06d", aleatorio.nextInt(1_000_000)) : null;
        log.info("Pasarela SIMULADA: orden={} tarjeta=****{} -> {}", solicitud.idOrden(),
                HuellaTarjeta.ultimos4(digitos), aprobado ? "APROBADO" : "RECHAZADO");
        return new Decision(aprobado, codigo);
    }
}
