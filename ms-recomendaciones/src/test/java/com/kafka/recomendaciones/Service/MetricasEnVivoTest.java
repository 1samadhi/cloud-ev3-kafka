package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.Config.CatalogoSemilla;
import com.kafka.recomendaciones.DTO.SnapshotMetricasDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Soporte.RelojMutable;
import org.junit.jupiter.api.Test;

import static com.kafka.recomendaciones.Soporte.evento;
import static org.assertj.core.api.Assertions.assertThat;

/** Ventanas deslizantes del dashboard, moviendo el reloj a mano. */
class MetricasEnVivoTest {

    private static final long MIN = 60_000L;

    private final RelojMutable reloj = new RelojMutable(1_800_000_000_000L);
    private final MetricasEnVivo metricas = new MetricasEnVivo(reloj, new CatalogoEnMemoria(new CatalogoSemilla()));

    private void ver(String usuario, String producto) {
        metricas.registrar(evento(usuario, TipoEvento.VER_PRODUCTO, producto, 1));
    }

    @Test
    void interaccionesPorMinutoSoloCuentaLosUltimos60s() {
        ver("a", "item_101");
        ver("a", "item_101");
        ver("b", "item_102");
        reloj.avanzar(61_000);
        ver("c", "item_103");
        ver("c", "item_103");

        SnapshotMetricasDTO s = metricas.snapshot();
        assertThat(s.getInteraccionesPorMinuto()).isEqualTo(2);
        assertThat(s.getTotalInteracciones()).isEqualTo(5);
        assertThat(s.getTipo()).isEqualTo("METRICAS");
    }

    @Test
    void usuariosActivosEsVentanaDe5Minutos() {
        ver("a", "item_101");
        reloj.avanzar(4 * MIN);
        ver("b", "item_101");
        ver("b", "item_102");
        assertThat(metricas.snapshot().getUsuariosActivos()).isEqualTo(2);

        reloj.avanzar(2 * MIN); // "a" quedo 6 min atras
        assertThat(metricas.snapshot().getUsuariosActivos()).isEqualTo(1);
    }

    @Test
    void pasados15MinutosLosEventosSalenDeTodasLasVentanasPeroNoDelTotal() {
        ver("a", "item_101");
        metricas.registrar(evento("a", TipoEvento.COMPRA, "item_101", 1));
        reloj.avanzar(15 * MIN);

        SnapshotMetricasDTO s = metricas.snapshot();
        assertThat(s.getEmbudo().getVistas()).isZero();
        assertThat(s.getEmbudo().getCompras()).isZero();
        assertThat(s.getTopProductos()).isEmpty();
        assertThat(s.getSerieMinutos()).allMatch(p -> p.getInteracciones() == 0);
        assertThat(s.getTotalInteracciones()).isEqualTo(2);
    }

    @Test
    void serieDe15PuntosPorMinuto() {
        ver("a", "item_101");                // hace 10 min
        reloj.avanzar(10 * MIN);
        ver("a", "item_101");                // minuto actual
        ver("b", "item_101");

        SnapshotMetricasDTO s = metricas.snapshot();
        assertThat(s.getSerieMinutos()).hasSize(15);
        assertThat(s.getSerieMinutos().get(14).getInteracciones()).isEqualTo(2);
        // Hace 10 min = punto 5: el punto i cubre [ahora-15min+i, ahora-15min+i+1).
        assertThat(s.getSerieMinutos().get(5).getInteracciones()).isEqualTo(1);
        assertThat(s.getSerieMinutos().stream().mapToLong(SnapshotMetricasDTO.PuntoSerie::getInteracciones).sum())
                .isEqualTo(3);
        // Del mas antiguo al actual, separados por un minuto.
        assertThat(s.getSerieMinutos().get(1).getInicio() - s.getSerieMinutos().get(0).getInicio()).isEqualTo(MIN);
    }

    @Test
    void topProductosEmbudoYPorTipo() {
        for (int i = 0; i < 4; i++) ver("u" + i, "item_107");
        for (int i = 0; i < 2; i++) ver("u" + i, "item_101");
        for (String p : new String[]{"item_102", "item_103", "item_104", "item_105"}) ver("x", p);
        metricas.registrar(evento("u0", TipoEvento.AGREGAR_CARRITO, "item_107", 1));
        metricas.registrar(evento("u0", TipoEvento.AGREGAR_CARRITO, "item_107", 1));
        metricas.registrar(evento("u0", TipoEvento.COMPRA, "item_107", 1));

        SnapshotMetricasDTO s = metricas.snapshot();
        assertThat(s.getTopProductos()).hasSize(5);
        assertThat(s.getTopProductos().get(0).getProductoId()).isEqualTo("item_107");
        assertThat(s.getTopProductos().get(0).getNombre()).isEqualTo("WH-1000XM5");
        assertThat(s.getTopProductos().get(0).getVistas()).isEqualTo(4);
        assertThat(s.getTopProductos().get(1).getProductoId()).isEqualTo("item_101");
        // Forma historica que lee la consola, mismo contenido.
        assertThat(s.getProductosMasVistos().get(0).getItemId()).isEqualTo("item_107");
        assertThat(s.getProductosMasVistos().get(0).getVistas()).isEqualTo(4);

        assertThat(s.getEmbudo().getVistas()).isEqualTo(10);
        assertThat(s.getEmbudo().getCarritos()).isEqualTo(2);
        assertThat(s.getEmbudo().getCompras()).isEqualTo(1);
        assertThat(s.getPorTipo()).containsOnlyKeys("VER_PRODUCTO", "AGREGAR_CARRITO", "APLICAR_CUPON",
                "GESTIONAR_ENVIO", "COMPRA");
        assertThat(s.getPorTipo()).containsEntry("APLICAR_CUPON", 0L).containsEntry("COMPRA", 1L);
    }
}
