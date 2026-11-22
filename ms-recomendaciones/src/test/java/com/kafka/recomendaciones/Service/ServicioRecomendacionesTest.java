package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.Config.CatalogoSemilla;
import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.ProductoEnriquecidoDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Repository.RepositoriosEnMemoria;
import com.kafka.recomendaciones.Soporte.RelojMutable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.kafka.recomendaciones.Soporte.evento;
import static org.assertj.core.api.Assertions.assertThat;

/** Orquestacion: persiste, recalcula, guarda; idempotente ante el reproceso. */
class ServicioRecomendacionesTest {

    private final RelojMutable reloj = new RelojMutable(1_800_000_000_000L);
    private final RepositoriosEnMemoria repo = new RepositoriosEnMemoria();
    private final ServicioRecomendaciones servicio = new ServicioRecomendaciones(repo, repo,
            new CatalogoEnMemoria(new CatalogoSemilla()), new MotorRecomendaciones(), reloj, 100, 5);

    @Test
    void procesarGuardaHistorialYTopEnriquecido() {
        EventoInteraccionDTO e = evento("u1", TipoEvento.VER_PRODUCTO, "item_107", reloj.millis());
        List<ProductoEnriquecidoDTO> top = servicio.procesarEvento(e);

        assertThat(top).hasSize(5);
        ProductoEnriquecidoDTO primero = top.get(0);
        assertThat(primero.getRankPosition()).isEqualTo(1);
        assertThat(primero.getItemId()).isEqualTo("item_108");
        assertThat(primero.getNombre()).isEqualTo("AirPods Pro 2");
        assertThat(primero.getPrecio()).isEqualTo(249990.0);
        assertThat(primero.getSubcategoria()).isEqualTo("audifonos");
        assertThat(primero.getStock()).isEqualTo(12);
        assertThat(primero.getMotivo()).isEqualTo("Porque viste WH-1000XM5");
        assertThat(repo.obtener("u1")).contains(top);

        // El mismo evento reprocesado (reintento/rebalanceo) no duplica el historial.
        servicio.procesarEvento(e);
        assertThat(repo.ultimos("u1", 100)).hasSize(1);
    }

    @Test
    void usuarioSinTopRecibePopularesSinPersistir() {
        List<ProductoEnriquecidoDTO> top = servicio.obtener("nuevo");
        assertThat(top).hasSize(5).allMatch(p -> p.getMotivo().equals(MotorRecomendaciones.MOTIVO_POPULAR));
        assertThat(repo.obtener("nuevo")).isEmpty();
    }
}
