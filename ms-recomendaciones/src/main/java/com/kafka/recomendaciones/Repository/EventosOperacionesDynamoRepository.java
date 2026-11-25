package com.kafka.recomendaciones.Repository;

import com.kafka.recomendaciones.DTO.SnapshotMetricasDTO;
import com.kafka.recomendaciones.Model.EventoOperacionesItem;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Escribe el snapshot de metricas en EventosOperaciones con las claves del Terraform. */
public class EventosOperacionesDynamoRepository implements EventosOperacionesRepository {

    static final TableSchema<EventoOperacionesItem> ESQUEMA = TableSchema.fromBean(EventoOperacionesItem.class);

    /**
     * Milisegundos SIEMPRE presentes: Instant.toString() los omite cuando valen
     * cero, y "...:05Z" quedaria despues de "...:05.120Z" en orden lexicografico,
     * desordenando la SK.
     */
    private static final DateTimeFormatter ISO_MILIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

    private final DynamoDbTable<EventoOperacionesItem> tabla;
    private final long ttlSegundos;

    public EventosOperacionesDynamoRepository(DynamoDbEnhancedClient cliente, String nombreTabla, long ttlHoras) {
        this.tabla = cliente.table(nombreTabla, ESQUEMA);
        this.ttlSegundos = ttlHoras * 3600;
    }

    @Override
    public void guardar(SnapshotMetricasDTO snapshot) {
        tabla.putItem(aItem(snapshot, ttlSegundos));
    }

    static String ocurridoEn(long epochMillis) {
        return ISO_MILIS.format(Instant.ofEpochMilli(epochMillis));
    }

    static EventoOperacionesItem aItem(SnapshotMetricasDTO s, long ttlSegundos) {
        EventoOperacionesItem item = new EventoOperacionesItem();
        item.setTipo(EventoOperacionesItem.TIPO_METRICAS);
        item.setOcurridoEn(ocurridoEn(s.getTimestamp()));
        item.setId(UUID.randomUUID().toString());
        item.setOrigen("ms-recomendaciones");
        // El TTL de DynamoDB se mide en SEGUNDOS epoch; en milisegundos el item
        // "expiraria" en el ano 50000 y nunca se borraria.
        item.setExpiraEn(s.getTimestamp() / 1000 + ttlSegundos);
        item.setTimestamp(s.getTimestamp());
        item.setInteraccionesPorMinuto(s.getInteraccionesPorMinuto());
        item.setUsuariosActivos(s.getUsuariosActivos());
        item.setTotalInteracciones(s.getTotalInteracciones());

        item.setSerieMinutos(s.getSerieMinutos().stream().map(p -> {
            EventoOperacionesItem.PuntoSerie x = new EventoOperacionesItem.PuntoSerie();
            x.setInicio(p.getInicio());
            x.setInteracciones(p.getInteracciones());
            return x;
        }).toList());

        item.setTopProductos(s.getTopProductos().stream().map(p -> {
            EventoOperacionesItem.TopProducto x = new EventoOperacionesItem.TopProducto();
            x.setProductoId(p.getProductoId());
            x.setNombre(p.getNombre());
            x.setCategoria(p.getCategoria());
            x.setVistas(p.getVistas());
            return x;
        }).toList());

        item.setProductosMasVistos(s.getProductosMasVistos().stream().map(p -> {
            EventoOperacionesItem.ProductoVisto x = new EventoOperacionesItem.ProductoVisto();
            x.setItemId(p.getItemId());
            x.setNombre(p.getNombre());
            x.setCategoria(p.getCategoria());
            x.setVistas(p.getVistas());
            return x;
        }).toList());

        EventoOperacionesItem.Embudo embudo = new EventoOperacionesItem.Embudo();
        embudo.setVistas(s.getEmbudo().getVistas());
        embudo.setCarritos(s.getEmbudo().getCarritos());
        embudo.setCompras(s.getEmbudo().getCompras());
        item.setEmbudo(embudo);

        item.setPorTipo(new LinkedHashMap<>(s.getPorTipo()));
        return item;
    }
}
