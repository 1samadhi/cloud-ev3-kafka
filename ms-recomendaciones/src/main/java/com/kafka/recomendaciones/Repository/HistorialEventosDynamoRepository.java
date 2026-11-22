package com.kafka.recomendaciones.Repository;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Model.EventoHistorialItem;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.util.List;

/**
 * HistorialEventos sobre DynamoDB con el enhanced client.
 *
 * No lleva @Repository: lo instancia DynamoDbConfig con el nombre de tabla de la
 * configuracion, y solo cuando app.dynamodb.habilitado=true.
 */
public class HistorialEventosDynamoRepository implements HistorialEventosRepository {

    private static final TableSchema<EventoHistorialItem> ESQUEMA = TableSchema.fromBean(EventoHistorialItem.class);

    private final DynamoDbTable<EventoHistorialItem> tabla;

    public HistorialEventosDynamoRepository(DynamoDbEnhancedClient cliente, String nombreTabla) {
        this.tabla = cliente.table(nombreTabla, ESQUEMA);
    }

    @Override
    public void guardar(EventoInteraccionDTO evento) {
        tabla.putItem(aItem(evento));
    }

    @Override
    public List<EventoInteraccionDTO> ultimos(String usuarioId, int limite) {
        // Descendente por la SK (instante con ceros a la izquierda) y con limite:
        // DynamoDB lee solo esos items, no la historia completa del usuario.
        QueryEnhancedRequest consulta = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(usuarioId).build()))
                .scanIndexForward(false)
                .limit(limite)
                .build();
        return tabla.query(consulta).items().stream()
                .limit(limite)
                .map(HistorialEventosDynamoRepository::aEvento)
                .toList();
    }

    static EventoHistorialItem aItem(EventoInteraccionDTO e) {
        EventoHistorialItem item = new EventoHistorialItem();
        item.setUsuarioId(e.getUsuarioId());
        item.setInstanteEvento(EventoHistorialItem.claveOrden(e.getTimestamp(), e.getIdEvento()));
        item.setIdEvento(e.getIdEvento());
        item.setTipoEvento(e.getTipoEvento() != null ? e.getTipoEvento().name() : null);
        item.setProductoId(e.getProductoId());
        item.setCategoria(e.getCategoria());
        item.setSubcategoria(e.getSubcategoria());
        item.setMarca(e.getMarca());
        item.setOrigen(e.getOrigen());
        // DynamoDB no acepta un mapa vacio como valor util: se omite en ese caso.
        item.setDetalle(e.getDetalle() == null || e.getDetalle().isEmpty() ? null : e.getDetalle());
        item.setTimestamp(e.getTimestamp());
        return item;
    }

    static EventoInteraccionDTO aEvento(EventoHistorialItem i) {
        return EventoInteraccionDTO.builder()
                .idEvento(i.getIdEvento())
                .usuarioId(i.getUsuarioId())
                .tipoEvento(i.getTipoEvento() != null ? TipoEvento.valueOf(i.getTipoEvento()) : null)
                .productoId(i.getProductoId())
                .categoria(i.getCategoria())
                .subcategoria(i.getSubcategoria())
                .marca(i.getMarca())
                .origen(i.getOrigen())
                .detalle(i.getDetalle())
                .timestamp(i.getTimestamp() != null ? i.getTimestamp() : 0L)
                .build();
    }
}
