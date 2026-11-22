package com.pedidos360.productos.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pedidos360.productos.DTO.NivelStock;

/** Reglas de dominio puras: stock nunca negativo, semáforo de stock y ciclo de vida de la reserva. */
class DominioTest {

    private static Producto producto(int stock) {
        return Producto.builder().id("item_x").nombre("X").categoria("audio").precio(1000L).stock(stock)
                .actualizadoEn(Instant.EPOCH).build();
    }

    @Test
    void descontarMasDeLoDisponibleFallaYNoModificaElStock() {
        Producto p = producto(3);
        assertThatThrownBy(() -> p.descontarStock(4, Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThat(p.getStock()).isEqualTo(3);
        assertThat(p.getActualizadoEn()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void descontarYReponerActualizanStockYFecha() {
        Producto p = producto(3);
        Instant t = Instant.parse("2026-11-22T12:00:00Z");
        p.descontarStock(3, t);
        assertThat(p.getStock()).isZero();
        p.reponerStock(5, t);
        assertThat(p.getStock()).isEqualTo(5);
        assertThat(p.getActualizadoEn()).isEqualTo(t);
        assertThatThrownBy(() -> p.reponerStock(0, t)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nivelDeStock() {
        assertThat(NivelStock.clasificar(0, 10)).isEqualTo(NivelStock.AGOTADO);
        assertThat(NivelStock.clasificar(1, 10)).isEqualTo(NivelStock.BAJO);
        assertThat(NivelStock.clasificar(10, 10)).isEqualTo(NivelStock.BAJO);
        assertThat(NivelStock.clasificar(11, 10)).isEqualTo(NivelStock.NORMAL);
    }

    @Test
    void unaReservaNoSePuedeLiberarDosVeces() {
        ReservaStock r = ReservaStock.nueva("P-1", Map.of("item_b", 1, "item_a", 2), Instant.now());
        assertThat(r.cantidadesPorProducto().keySet()).containsExactly("item_a", "item_b");
        r.marcarLiberada(Instant.now());
        assertThat(r.getEstado()).isEqualTo(EstadoReserva.LIBERADA);
        assertThatThrownBy(() -> r.marcarLiberada(Instant.now())).isInstanceOf(IllegalStateException.class);
    }
}
