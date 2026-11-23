package com.kafka.pago.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La regla del enunciado en JUnit puro: "misma tarjeta, más de 3 veces en 10 s".
 * Se simula la secuencia completa: cada intento se evalúa contra los anteriores
 * y luego se agrega, igual que hace ServicioAntifraude con la tabla.
 */
class VentanaAntifraudeTest {

    private static final Instant T0 = Instant.parse("2026-11-22T12:00:00Z");
    private final VentanaAntifraude ventana = new VentanaAntifraude(Duration.ofSeconds(10), 3);

    /** Evalúa los intentos en orden y devuelve la decisión de cada uno. */
    private List<VentanaAntifraude.Decision> secuencia(long... milisDesdeT0) {
        List<Instant> registrados = new ArrayList<>();
        List<VentanaAntifraude.Decision> decisiones = new ArrayList<>();
        for (long ms : milisDesdeT0) {
            Instant actual = T0.plusMillis(ms);
            decisiones.add(ventana.evaluar(registrados, actual));
            registrados.add(actual);
        }
        return decisiones;
    }

    @Test
    @DisplayName("3 intentos en 10 s NO bloquean: '3 veces' no es 'más de 3'")
    void tresEnDiezSegundosNoBloquea() {
        var d = secuencia(0, 1000, 2000);
        assertThat(d).noneMatch(VentanaAntifraude.Decision::bloquear);
        assertThat(d.get(2).intentosEnVentana()).isEqualTo(3);
    }

    @Test
    @DisplayName("el 4.º intento dentro de 10 s bloquea")
    void cuartoEnDiezSegundosBloquea() {
        var d = secuencia(0, 1000, 2000, 3000);
        assertThat(d.subList(0, 3)).noneMatch(VentanaAntifraude.Decision::bloquear);
        assertThat(d.get(3).bloquear()).isTrue();
        assertThat(d.get(3).intentosEnVentana()).isEqualTo(4);
    }

    @Test
    @DisplayName("4 intentos repartidos en 11 s NO bloquean: la ventana desliza")
    void cuatroEnOnceSegundosNoBloquea() {
        // Al llegar el 4.º (t=11 s) el 1.º (t=0) ya quedó fuera de (1 s, 11 s].
        var d = secuencia(0, 3667, 7333, 11000);
        assertThat(d).noneMatch(VentanaAntifraude.Decision::bloquear);
        assertThat(d.get(3).intentosEnVentana()).isEqualTo(3);
    }

    @Test
    @DisplayName("un intento ocurrido exactamente 10 s antes ya salió de la ventana")
    void limiteExactoExcluido() {
        var d = secuencia(0, 1, 2, 10000);
        assertThat(d.get(3).bloquear()).isFalse();
        assertThat(d.get(3).intentosEnVentana()).isEqualTo(3);
    }

    @Test
    @DisplayName("ventana deslizante real: no compara sólo contra el último intento")
    void noEsContadorDelUltimoIntento() {
        // El código viejo reiniciaba si pasaban > 10 s desde el ÚLTIMO; acá
        // los 4 intentos están en 9,9 s y el hueco entre ellos no importa.
        var d = secuencia(0, 9000, 9500, 9900);
        assertThat(d.get(3).bloquear()).isTrue();
    }

    @Test
    void ignoraInstantesFueraDeVentanaYNulos() {
        List<Instant> previos = new ArrayList<>(List.of(
                T0.minusSeconds(60), T0.minusSeconds(11), T0.plusSeconds(5)));
        previos.add(null);
        var d = ventana.evaluar(previos, T0);
        assertThat(d.intentosEnVentana()).isEqualTo(1);
        assertThat(d.bloquear()).isFalse();
    }

    @Test
    void umbralYVentanaConfigurables() {
        var estricta = new VentanaAntifraude(Duration.ofSeconds(30), 1);
        assertThat(estricta.evaluar(List.of(T0.minusSeconds(29)), T0).bloquear()).isTrue();
        assertThat(estricta.evaluar(List.of(), T0).bloquear()).isFalse();
    }

    @Test
    void rechazaParametrosInvalidos() {
        assertThatThrownBy(() -> new VentanaAntifraude(Duration.ZERO, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VentanaAntifraude(Duration.ofSeconds(10), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
