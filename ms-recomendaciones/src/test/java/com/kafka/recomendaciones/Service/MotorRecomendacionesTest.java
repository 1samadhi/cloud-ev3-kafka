package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.Config.CatalogoSemilla;
import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Model.Producto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.kafka.recomendaciones.Soporte.evento;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Motor de similitud (§5.2) con JUnit puro: catalogo semilla, sin Spring. */
class MotorRecomendacionesTest {

    private static final long AHORA = 1_800_000_000_000L;
    private static final long HORA = 3_600_000L;

    private final MotorRecomendaciones motor = new MotorRecomendaciones();
    private final List<Producto> catalogo = new CatalogoSemilla().productos();

    private List<MotorRecomendaciones.Recomendacion> recomendar(List<EventoInteraccionDTO> historial) {
        return motor.recomendar(historial, catalogo, Map.of(), AHORA, 5);
    }

    private static List<String> ids(List<MotorRecomendaciones.Recomendacion> r) {
        return r.stream().map(x -> x.producto().getId()).toList();
    }

    @Test
    void recomiendaLoParecidoPorEncimaDeLoYaVisto() {
        // Vio los WH-1000XM5 (Sony, audio/audifonos).
        var r = recomendar(List.of(evento("u", TipoEvento.VER_PRODUCTO, "item_107", AHORA)));

        // Los AirPods (misma subcategoria) ganan al producto ya visto, que entra con -20 %.
        assertThat(ids(r).get(0)).isEqualTo("item_108");
        assertThat(ids(r)).containsSubsequence("item_108", "item_107");
        assertThat(r.get(0).score()).isEqualTo(5.0);       // 3*1 + 2*1 + 1*0
        assertThat(r.get(1).score()).isEqualTo(4.8);       // (3+2+1) * 0.8
        assertThat(r.get(0).motivo()).isEqualTo("Porque viste WH-1000XM5");
    }

    @Test
    void noRecomiendaLoYaComprado() {
        var r = recomendar(List.of(evento("u", TipoEvento.COMPRA, "item_101", AHORA)));

        assertThat(ids(r)).doesNotContain("item_101");
        assertThat(ids(r).get(0)).isEqualTo("item_102");
        assertThat(r.get(0).motivo()).isEqualTo("Porque compraste Galaxy S24");
    }

    @Test
    void loCompradoSigueExcluidoAunqueTambienSeHayaVisto() {
        var r = recomendar(List.of(
                evento("u", TipoEvento.VER_PRODUCTO, "item_110", AHORA - 1000),
                evento("u", TipoEvento.AGREGAR_CARRITO, "item_110", AHORA - 500),
                evento("u", TipoEvento.COMPRA, "item_110", AHORA)));

        assertThat(ids(r)).doesNotContain("item_110").startsWith("item_111");
    }

    @Test
    void excluyeProductosSinStock() {
        List<Producto> sinAirPods = new ArrayList<>();
        for (Producto p : catalogo) {
            sinAirPods.add(p.getId().equals("item_108") ? p.toBuilder().stock(0).build() : p);
        }
        var r = motor.recomendar(List.of(evento("u", TipoEvento.VER_PRODUCTO, "item_107", AHORA)),
                sinAirPods, Map.of(), AHORA, 5);

        assertThat(ids(r)).doesNotContain("item_108").startsWith("item_107");
    }

    @Test
    void motivosPorCategoriaYPorMarca() {
        var r = recomendar(List.of(evento("u", TipoEvento.VER_PRODUCTO, "item_107", AHORA)));

        // Flip 6: misma categoria (audio) pero otra subcategoria -> afinidad por categoria.
        var flip = r.stream().filter(x -> x.producto().getId().equals("item_109")).findFirst().orElseThrow();
        assertThat(flip.motivo()).isEqualTo("Te interesa Audio (100 %)");
        // PS5: solo coincide la marca Sony.
        var ps5 = r.stream().filter(x -> x.producto().getId().equals("item_110")).findFirst().orElseThrow();
        assertThat(ps5.motivo()).isEqualTo("Marca que sueles revisar: Sony");
        assertThat(r).hasSize(5);
    }

    @Test
    void sinHistorialRecomiendaLoPopular() {
        var r = motor.recomendar(List.of(), catalogo, Map.of("item_114", 10.0, "item_116", 4.0), AHORA, 5);

        assertThat(ids(r)).startsWith("item_114", "item_116");
        assertThat(r).allMatch(x -> x.motivo().equals(MotorRecomendaciones.MOTIVO_POPULAR));
    }

    @Test
    void loRecientePesaMasQueLoAntiguo() {
        assertThat(MotorRecomendaciones.decaimiento(24 * HORA)).isCloseTo(0.5, within(1e-9));
        assertThat(MotorRecomendaciones.decaimiento(0)).isEqualTo(1.0);

        // Gaming visto (y en el carrito) hace 3 dias; audio visto recien: manda audio.
        var r = recomendar(List.of(
                evento("u", TipoEvento.AGREGAR_CARRITO, "item_111", AHORA - 72 * HORA),
                evento("u", TipoEvento.VER_PRODUCTO, "item_107", AHORA)));
        assertThat(ids(r).get(0)).isEqualTo("item_108");
    }

    @Test
    void cuponSeAplicaALosProductosDelCarritoYEnvioNoSuma() {
        EventoInteraccionDTO cupon = EventoInteraccionDTO.builder()
                .idEvento("c1").usuarioId("u").tipoEvento(TipoEvento.APLICAR_CUPON)
                .detalle(Map.of("cupon", "X", "productos", "item_104, item_105"))
                .timestamp(AHORA).build();
        EventoInteraccionDTO envio = EventoInteraccionDTO.builder()
                .idEvento("e1").usuarioId("u").tipoEvento(TipoEvento.GESTIONAR_ENVIO)
                .productoId("item_110").timestamp(AHORA).build();

        assertThat(MotorRecomendaciones.productosDe(cupon)).containsExactly("item_104", "item_105");
        assertThat(MotorRecomendaciones.peso(TipoEvento.GESTIONAR_ENVIO)).isZero();

        var r = recomendar(List.of(cupon, envio));
        // Perfil 100 % laptops/ultraliviana: las del carrito siguen arriba (4.4 con el -20 %),
        // luego el IdeaPad (solo categoria, 3.0) y despues Apple por marca. El envio
        // con item_110 no sumo nada: la PS5 no aparece.
        assertThat(ids(r)).startsWith("item_105", "item_104", "item_106").doesNotContain("item_110");
        assertThat(r.get(0).score()).isEqualTo(4.4);
        assertThat(r.get(2).score()).isEqualTo(3.0);
    }

    @Test
    void pesosDelContrato() {
        assertThat(MotorRecomendaciones.peso(TipoEvento.VER_PRODUCTO)).isEqualTo(1.0);
        assertThat(MotorRecomendaciones.peso(TipoEvento.APLICAR_CUPON)).isEqualTo(0.5);
        assertThat(MotorRecomendaciones.peso(TipoEvento.AGREGAR_CARRITO)).isEqualTo(3.0);
        assertThat(MotorRecomendaciones.peso(TipoEvento.COMPRA)).isEqualTo(5.0);
    }
}
