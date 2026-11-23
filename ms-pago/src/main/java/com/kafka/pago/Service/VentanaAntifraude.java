package com.kafka.pago.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * La regla antifraude en estado puro: sin Spring, sin base, sin reloj del
 * sistema. Recibe los instantes ya registrados de una huella y el instante del
 * intento nuevo, y decide. Así se prueba con JUnit plano y la regla del
 * enunciado ("misma tarjeta, más de 3 veces en 10 s") queda en un solo lugar.
 *
 * Ventana deslizante semiabierta (t − ventana, t]: cuenta todo intento
 * ESTRICTAMENTE posterior a t − 10 s, más el actual. Un intento ocurrido
 * exactamente 10 s antes ya salió de la ventana.
 *
 * "Más de umbral": con umbral 3, el 3.º intento todavía es legítimo (una
 * persona que se equivoca dos veces al tipear) y el 4.º es el que bloquea.
 * El código anterior bloqueaba en el 3.º, que es "3 o más", no "más de 3".
 */
public final class VentanaAntifraude {

    private final Duration ventana;
    private final int umbral;

    public VentanaAntifraude(Duration ventana, int umbral) {
        Objects.requireNonNull(ventana, "ventana");
        if (ventana.isZero() || ventana.isNegative()) {
            throw new IllegalArgumentException("La ventana debe ser positiva");
        }
        if (umbral < 1) {
            throw new IllegalArgumentException("El umbral debe ser al menos 1");
        }
        this.ventana = ventana;
        this.umbral = umbral;
    }

    /** Límite inferior (exclusivo) de la ventana que termina en {@code instanteActual}. */
    public Instant inicioVentana(Instant instanteActual) {
        return instanteActual.minus(ventana);
    }

    /**
     * @param instantesPrevios intentos ya registrados de la misma huella (puede
     *                         traer instantes fuera de la ventana: se filtran acá)
     * @param instanteActual   instante del intento que se está evaluando; se cuenta
     *                         siempre (la regla dice "incluyéndolo")
     */
    public Decision evaluar(Collection<Instant> instantesPrevios, Instant instanteActual) {
        Objects.requireNonNull(instanteActual, "instanteActual");
        Instant inicio = inicioVentana(instanteActual);
        long previosEnVentana = instantesPrevios == null ? 0 : instantesPrevios.stream()
                .filter(Objects::nonNull)
                .filter(instante -> instante.isAfter(inicio) && !instante.isAfter(instanteActual))
                .count();
        int total = (int) previosEnVentana + 1;
        return new Decision(total, total > umbral);
    }

    public Duration getVentana() {
        return ventana;
    }

    public int getUmbral() {
        return umbral;
    }

    /** Resultado de evaluar un intento: cuántos hay en la ventana (con él) y si se bloquea. */
    public record Decision(int intentosEnVentana, boolean bloquear) {
    }
}
