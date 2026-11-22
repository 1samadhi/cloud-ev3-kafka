package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.Config.CatalogoSemilla;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Repository.EventosOperacionesRepository;
import com.kafka.recomendaciones.Repository.RepositoriosEnMemoria;
import com.kafka.recomendaciones.Soporte.RelojMutable;
import org.junit.jupiter.api.Test;

import static com.kafka.recomendaciones.Soporte.evento;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** Snapshot a EventosOperaciones: solo cuando cambia, y un fallo de DynamoDB no rompe nada. */
class PublicadorOperacionesTest {

    private final RelojMutable reloj = new RelojMutable(1_800_000_000_000L);
    private final MetricasEnVivo metricas = new MetricasEnVivo(reloj, new CatalogoEnMemoria(new CatalogoSemilla()));

    @Test
    void noEscribeSiLasMetricasNoCambiaron() {
        RepositoriosEnMemoria repo = new RepositoriosEnMemoria();
        PublicadorOperaciones publicador = new PublicadorOperaciones(metricas, repo);

        assertThat(publicador.publicar()).isTrue();
        reloj.avanzar(5_000); // cambia el timestamp, no los valores
        assertThat(publicador.publicar()).isFalse();

        metricas.registrar(evento("u", TipoEvento.VER_PRODUCTO, "item_101", 1));
        assertThat(publicador.publicar()).isTrue();
        assertThat(repo.snapshotsPublicados()).hasSize(2);
    }

    @Test
    void unFalloDeDynamoSeLogueaYSeReintentaEnElSiguienteCiclo() {
        EventosOperacionesRepository repo = mock(EventosOperacionesRepository.class);
        doThrow(new RuntimeException("DynamoDB caido")).when(repo).guardar(any());
        PublicadorOperaciones publicador = new PublicadorOperaciones(metricas, repo);

        assertThat(publicador.publicar()).isFalse();
        assertThat(publicador.publicar()).isFalse(); // la firma no se guardo: vuelve a intentar
    }
}
