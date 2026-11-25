package com.pedidos360.carrito.Service;

import com.pedidos360.carrito.Exception.TransicionInvalidaException;
import com.pedidos360.carrito.Model.EstadoPedido;
import com.pedidos360.carrito.Model.MetodoEnvio;
import com.pedidos360.carrito.Model.Pedido;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;

import static com.pedidos360.carrito.Model.EstadoPedido.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Máquina de estados del pedido (§8), JUnit puro. */
class MaquinaEstadosPedidoTest {

    @Test
    void flujoCompletoDeEnvioEsValido() {
        List<EstadoPedido> camino = List.of(PENDIENTE_PAGO, PAGADO, PREPARANDO, ENVIADO, EN_CAMINO, ENTREGADO);
        for (int i = 0; i < camino.size() - 1; i++) {
            assertThat(MaquinaEstadosPedido.esValida(camino.get(i), camino.get(i + 1), MetodoEnvio.ENVIO))
                    .as(camino.get(i) + " → " + camino.get(i + 1)).isTrue();
        }
    }

    @Test
    void flujoCompletoDeRetiroEsValido() {
        List<EstadoPedido> camino = List.of(PENDIENTE_PAGO, PAGADO, PREPARANDO, LISTO_RETIRO, RETIRADO);
        for (int i = 0; i < camino.size() - 1; i++) {
            assertThat(MaquinaEstadosPedido.esValida(camino.get(i), camino.get(i + 1), MetodoEnvio.RETIRO))
                    .as(camino.get(i) + " → " + camino.get(i + 1)).isTrue();
        }
    }

    @Test
    void pagoRechazadoSoloDesdePendiente() {
        assertThat(MaquinaEstadosPedido.esValida(PENDIENTE_PAGO, PAGO_RECHAZADO, MetodoEnvio.ENVIO)).isTrue();
        assertThat(MaquinaEstadosPedido.esValida(PENDIENTE_PAGO, PAGO_RECHAZADO, MetodoEnvio.RETIRO)).isTrue();
        assertThat(MaquinaEstadosPedido.esValida(PAGADO, PAGO_RECHAZADO, MetodoEnvio.ENVIO)).isFalse();
    }

    @Test
    void noSeMezclanLosFlujosDeEnvioYRetiro() {
        assertThat(MaquinaEstadosPedido.esValida(PREPARANDO, ENVIADO, MetodoEnvio.RETIRO)).isFalse();
        assertThat(MaquinaEstadosPedido.esValida(PREPARANDO, LISTO_RETIRO, MetodoEnvio.ENVIO)).isFalse();
    }

    @Test
    void noSeSaltanPasosNiSeRetrocede() {
        assertThat(MaquinaEstadosPedido.esValida(PENDIENTE_PAGO, PREPARANDO, MetodoEnvio.ENVIO)).isFalse();
        assertThat(MaquinaEstadosPedido.esValida(PAGADO, ENVIADO, MetodoEnvio.ENVIO)).isFalse();
        assertThat(MaquinaEstadosPedido.esValida(ENVIADO, PREPARANDO, MetodoEnvio.ENVIO)).isFalse();
        assertThat(MaquinaEstadosPedido.esValida(PAGADO, PAGADO, MetodoEnvio.ENVIO)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"ENTREGADO", "RETIRADO", "PAGO_RECHAZADO"})
    void losEstadosFinalesNoTienenSalida(EstadoPedido fin) {
        assertThat(MaquinaEstadosPedido.esFinal(fin)).isTrue();
        for (EstadoPedido destino : EstadoPedido.values()) {
            assertThat(MaquinaEstadosPedido.esValida(fin, destino, MetodoEnvio.ENVIO)).isFalse();
            assertThat(MaquinaEstadosPedido.esValida(fin, destino, MetodoEnvio.RETIRO)).isFalse();
        }
    }

    @Test
    void validarLanzaTransicionInvalida() {
        assertThatThrownBy(() -> MaquinaEstadosPedido.validar(ENTREGADO, PREPARANDO, MetodoEnvio.ENVIO))
                .isInstanceOf(TransicionInvalidaException.class)
                .hasMessageContaining("ENTREGADO").hasMessageContaining("PREPARANDO");
    }

    @Test
    void siguienteLogisticoNoAvanzaPendientesNiFinales() {
        assertThat(MaquinaEstadosPedido.siguienteLogistico(PAGADO, MetodoEnvio.ENVIO)).contains(PREPARANDO);
        assertThat(MaquinaEstadosPedido.siguienteLogistico(PREPARANDO, MetodoEnvio.RETIRO)).contains(LISTO_RETIRO);
        assertThat(MaquinaEstadosPedido.siguienteLogistico(PENDIENTE_PAGO, MetodoEnvio.ENVIO)).isEmpty();
        assertThat(MaquinaEstadosPedido.siguienteLogistico(ENTREGADO, MetodoEnvio.ENVIO)).isEmpty();
    }

    @Test
    void pedidoRegistraHistorialYFechaDePago() {
        Pedido p = new Pedido();
        p.setMetodoEnvio(MetodoEnvio.RETIRO);
        Instant t0 = Instant.parse("2026-11-20T10:00:00Z");
        p.iniciar(t0);
        p.cambiarEstado(PAGADO, t0.plusSeconds(60));
        assertThat(p.getPagadoEn()).isEqualTo(t0.plusSeconds(60));
        assertThat(p.getHistorial()).extracting(h -> h.getEstado()).containsExactly(PENDIENTE_PAGO, PAGADO);
        assertThatThrownBy(() -> p.cambiarEstado(ENVIADO, t0.plusSeconds(120)))
                .isInstanceOf(TransicionInvalidaException.class);
        assertThat(p.getEstado()).isEqualTo(PAGADO);
    }
}
