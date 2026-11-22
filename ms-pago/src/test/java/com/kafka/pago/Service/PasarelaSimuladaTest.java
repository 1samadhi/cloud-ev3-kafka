package com.kafka.pago.Service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.kafka.pago.Service.PasarelaPago.Decision;
import com.kafka.pago.Service.PasarelaPago.SolicitudCobro;

class PasarelaSimuladaTest {

    private final PasarelaSimulada pasarela = new PasarelaSimulada();

    private Decision cobrar(String numero) {
        return (Decision) pasarela.iniciar(new SolicitudCobro("TX-1", "ismael", 1000, "http://x", numero));
    }

    @Test
    void apruebaConCodigoDeSeisDigitos() {
        Decision d = cobrar("4051885600446623");
        assertThat(d.aprobado()).isTrue();
        assertThat(d.codigoAutorizacion()).matches("^\\d{6}$");
    }

    @Test
    void rechazaTarjetasTerminadasEn0002() {
        Decision d = cobrar("4000 0000 0000 0002");
        assertThat(d.aprobado()).isFalse();
        assertThat(d.codigoAutorizacion()).isNull();
    }

    @Test
    void elToStringDeLaSolicitudNoExponeElNumero() {
        String texto = new SolicitudCobro("TX-1", "ismael", 1000, "http://x", "4051885600446623").toString();
        assertThat(texto).doesNotContain("4051885600446623").contains("****6623");
    }
}
