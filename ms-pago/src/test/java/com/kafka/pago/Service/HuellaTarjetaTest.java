package com.kafka.pago.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HuellaTarjetaTest {

    private static final String NUMERO = "4051885600446623";
    private final HuellaTarjeta huellas = new HuellaTarjeta("pepper-de-prueba");

    @Test
    void esSha256HexDeterministicaYNoContieneElNumero() {
        String h = huellas.deTarjeta(NUMERO);
        assertThat(h).matches("^[0-9a-f]{64}$");
        assertThat(huellas.deTarjeta(NUMERO)).isEqualTo(h);
        assertThat(h).doesNotContain(NUMERO).doesNotContain("6623");
    }

    @Test
    void normalizaEspaciosYGuiones() {
        assertThat(huellas.deTarjeta("4051 8856-0044 6623")).isEqualTo(huellas.deTarjeta(NUMERO));
    }

    @Test
    void elPepperCambiaLaHuella() {
        assertThat(new HuellaTarjeta("otro").deTarjeta(NUMERO)).isNotEqualTo(huellas.deTarjeta(NUMERO));
    }

    @Test
    void huellaDeCuentaNoColisionaConLaDeTarjeta() {
        assertThat(huellas.deCuenta("ismael")).matches("^[0-9a-f]{64}$")
                .isEqualTo(huellas.deCuenta(" ismael "))
                .isNotEqualTo(huellas.deCuenta("otro"))
                .isNotEqualTo(huellas.deTarjeta(NUMERO));
    }

    @Test
    void ultimos4YAbreviar() {
        assertThat(HuellaTarjeta.ultimos4("4051 8856 0044 6623")).isEqualTo("6623");
        assertThat(HuellaTarjeta.ultimos4("12")).isNull();
        assertThat(HuellaTarjeta.abreviar(huellas.deTarjeta(NUMERO))).hasSize(12);
        assertThat(HuellaTarjeta.abreviar(null)).isNull();
    }

    @Test
    void sinDigitosNoHayHuella() {
        assertThatThrownBy(() -> huellas.deTarjeta("----")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> huellas.deCuenta(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
