package com.kafka.recomendaciones.Repository;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.ProductoEnriquecidoDTO;
import com.kafka.recomendaciones.DTO.SnapshotMetricasDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Model.EventoHistorialItem;
import com.kafka.recomendaciones.Model.EventoOperacionesItem;
import com.kafka.recomendaciones.Model.RecomendacionesUsuarioItem;
import com.kafka.recomendaciones.Service.CatalogoEnMemoria;
import com.kafka.recomendaciones.Service.MetricasEnVivo;
import com.kafka.recomendaciones.Config.CatalogoSemilla;
import com.kafka.recomendaciones.Soporte.RelojMutable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.util.List;
import java.util.Map;

import static com.kafka.recomendaciones.Soporte.evento;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Repositorios DynamoDB con el enhanced client MOCKEADO: se verifica el mapeo a
 * items, las claves y la consulta, sin AWS ni DynamoDB Local.
 */
@SuppressWarnings("unchecked")
class RepositoriosDynamoTest {

    private final DynamoDbEnhancedClient cliente = mock(DynamoDbEnhancedClient.class);

    private <T> DynamoDbTable<T> tabla(String nombre) {
        DynamoDbTable<T> t = mock(DynamoDbTable.class);
        when(cliente.table(eq(nombre), any(TableSchema.class))).thenReturn((DynamoDbTable) t);
        return t;
    }

    @Test
    void historialGuardaConSkOrdenableYSinMapaVacio() {
        DynamoDbTable<EventoHistorialItem> t = tabla("HistorialEventos");
        HistorialEventosDynamoRepository repo = new HistorialEventosDynamoRepository(cliente, "HistorialEventos");

        EventoInteraccionDTO e = evento("u1", TipoEvento.VER_PRODUCTO, "item_101", 1_700_000_000_123L);
        e.setIdEvento("ev-1");
        repo.guardar(e);

        ArgumentCaptor<EventoHistorialItem> captor = ArgumentCaptor.forClass(EventoHistorialItem.class);
        verify(t).putItem(captor.capture());
        EventoHistorialItem item = captor.getValue();
        assertThat(item.getUsuarioId()).isEqualTo("u1");
        assertThat(item.getInstanteEvento()).isEqualTo("1700000000123#ev-1");
        assertThat(item.getTipoEvento()).isEqualTo("VER_PRODUCTO");
        assertThat(item.getDetalle()).isNull();
        // 13 digitos con ceros: el orden lexicografico coincide con el cronologico.
        assertThat(EventoHistorialItem.claveOrden(5L, "x")).isEqualTo("0000000000005#x");
    }

    @Test
    void historialConsultaDescendenteConLimite() {
        DynamoDbTable<EventoHistorialItem> t = tabla("HistorialEventos");
        HistorialEventosDynamoRepository repo = new HistorialEventosDynamoRepository(cliente, "HistorialEventos");
        EventoHistorialItem i1 = HistorialEventosDynamoRepository.aItem(evento("u1", TipoEvento.COMPRA, "item_102", 20));
        EventoHistorialItem i2 = HistorialEventosDynamoRepository.aItem(evento("u1", TipoEvento.VER_PRODUCTO, "item_101", 10));
        when(t.query(any(QueryEnhancedRequest.class)))
                .thenReturn(PageIterable.create(() -> List.of(Page.create(List.of(i1, i2))).iterator()));

        List<EventoInteraccionDTO> ultimos = repo.ultimos("u1", 100);

        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);
        verify(t).query(captor.capture());
        assertThat(captor.getValue().scanIndexForward()).isFalse();
        assertThat(captor.getValue().limit()).isEqualTo(100);
        assertThat(ultimos).extracting(EventoInteraccionDTO::getTipoEvento)
                .containsExactly(TipoEvento.COMPRA, TipoEvento.VER_PRODUCTO);
        assertThat(ultimos.get(0).getTimestamp()).isEqualTo(20);
    }

    @Test
    void recomendacionesIdaYVueltaYUsuarioSinItem() {
        DynamoDbTable<RecomendacionesUsuarioItem> t = tabla("RecomendacionesUsuario");
        RecomendacionesUsuarioDynamoRepository repo =
                new RecomendacionesUsuarioDynamoRepository(cliente, "RecomendacionesUsuario");
        ProductoEnriquecidoDTO dto = ProductoEnriquecidoDTO.builder().rankPosition(1).score(5.0)
                .itemId("item_108").nombre("AirPods Pro 2").categoria("audio").subcategoria("audifonos")
                .marca("Apple").precio(249990).calificacion(4.7).stock(12).motivo("Porque viste WH-1000XM5").build();

        repo.guardar("u1", List.of(dto), 99L);
        ArgumentCaptor<RecomendacionesUsuarioItem> captor = ArgumentCaptor.forClass(RecomendacionesUsuarioItem.class);
        verify(t).putItem(captor.capture());
        assertThat(captor.getValue().getUsuarioId()).isEqualTo("u1");
        assertThat(captor.getValue().getCalculadoEn()).isEqualTo(99L);
        assertThat(captor.getValue().getRecomendaciones()).hasSize(1);

        when(t.getItem(any(Key.class))).thenReturn(captor.getValue());
        assertThat(repo.obtener("u1")).contains(List.of(dto));

        when(t.getItem(any(Key.class))).thenReturn(null);
        assertThat(repo.obtener("otro")).isEmpty();
    }

    @Test
    void snapshotAEventosOperacionesConClavesDelTerraformYTtlEnSegundos() {
        DynamoDbTable<EventoOperacionesItem> t = tabla("EventosOperaciones-desarrollo");
        EventosOperacionesDynamoRepository repo =
                new EventosOperacionesDynamoRepository(cliente, "EventosOperaciones-desarrollo", 24);
        RelojMutable reloj = new RelojMutable(1_800_000_000_000L);
        MetricasEnVivo metricas = new MetricasEnVivo(reloj, new CatalogoEnMemoria(new CatalogoSemilla()));
        metricas.registrar(evento("u", TipoEvento.VER_PRODUCTO, "item_107", 1));
        SnapshotMetricasDTO s = metricas.snapshot();

        repo.guardar(s);
        ArgumentCaptor<EventoOperacionesItem> captor = ArgumentCaptor.forClass(EventoOperacionesItem.class);
        verify(t).putItem(captor.capture());
        EventoOperacionesItem item = captor.getValue();
        // La consola reconoce el broadcast de la Lambda por tipo === "metricas".
        assertThat(item.getTipo()).isEqualTo("metricas");
        assertThat(item.getOcurridoEn()).isEqualTo("2027-01-15T08:00:00.000Z");
        assertThat(item.getExpiraEn()).isEqualTo(1_800_000_000L + 24 * 3600);
        assertThat(item.getProductosMasVistos()).hasSize(1);
        assertThat(item.getProductosMasVistos().get(0).getItemId()).isEqualTo("item_107");
        assertThat(item.getInteraccionesPorMinuto()).isEqualTo(1L);
        assertThat(item.getSerieMinutos()).hasSize(15);
        assertThat(item.getPorTipo()).containsEntry("VER_PRODUCTO", 1L);
        assertThat(item.getEmbudo().getVistas()).isEqualTo(1L);
        // El esquema del bean se puede construir (anotaciones del enhanced client correctas).
        Map<String, ?> mapa = TableSchema.fromBean(EventoOperacionesItem.class).itemToMap(item, true);
        assertThat(mapa).containsKeys("tipo", "ocurridoEn", "expiraEn", "productosMasVistos", "embudo");
    }
}
