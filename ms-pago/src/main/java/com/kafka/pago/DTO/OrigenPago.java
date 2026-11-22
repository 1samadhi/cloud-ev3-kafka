package com.kafka.pago.DTO;

/**
 * Desde qué frontend se inició el pago. Decide a dónde se redirige al
 * navegador después del commit de Webpay.
 *
 * Es una lista CERRADA a propósito: el destino del redirect sale de la
 * configuración del servidor según este valor, nunca de una URL enviada por el
 * cliente (eso sería un open redirect explotable para phishing).
 */
public enum OrigenPago {
    TIENDA,
    CONSOLA;

    /**
     * Interpreta el parámetro que vuelve en el returnUrl. Cualquier valor
     * desconocido o ausente cae en CONSOLA en vez de fallar: el commit tiene
     * que redirigir siempre, aunque alguien haya manipulado el query string.
     */
    public static OrigenPago desdeTexto(String valor) {
        if (valor == null) {
            return CONSOLA;
        }
        for (OrigenPago origen : values()) {
            if (origen.name().equalsIgnoreCase(valor.trim())) {
                return origen;
            }
        }
        return CONSOLA;
    }
}
