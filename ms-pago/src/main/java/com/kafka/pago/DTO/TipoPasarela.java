package com.kafka.pago.DTO;

/**
 * Pasarela con la que se cobra. WEBPAY redirige al formulario de Transbank;
 * SIMULADA decide en el acto y existe para demostrar el antifraude por
 * tarjeta real (Webpay nunca nos entrega el número de la tarjeta).
 */
public enum TipoPasarela {
    WEBPAY,
    SIMULADA
}
