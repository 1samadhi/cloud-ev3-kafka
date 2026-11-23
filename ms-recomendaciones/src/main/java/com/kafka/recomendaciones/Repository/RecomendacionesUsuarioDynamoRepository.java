package com.kafka.recomendaciones.Repository;

import com.kafka.recomendaciones.DTO.ProductoEnriquecidoDTO;
import com.kafka.recomendaciones.Model.RecomendacionesUsuarioItem;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.List;
import java.util.Optional;

/** RecomendacionesUsuario sobre DynamoDB: un item por usuario con su top embebido. */
public class RecomendacionesUsuarioDynamoRepository implements RecomendacionesUsuarioRepository {

    private static final TableSchema<RecomendacionesUsuarioItem> ESQUEMA =
            TableSchema.fromBean(RecomendacionesUsuarioItem.class);

    private final DynamoDbTable<RecomendacionesUsuarioItem> tabla;

    public RecomendacionesUsuarioDynamoRepository(DynamoDbEnhancedClient cliente, String nombreTabla) {
        this.tabla = cliente.table(nombreTabla, ESQUEMA);
    }

    @Override
    public void guardar(String usuarioId, List<ProductoEnriquecidoDTO> recomendaciones, long calculadoEn) {
        RecomendacionesUsuarioItem item = new RecomendacionesUsuarioItem();
        item.setUsuarioId(usuarioId);
        item.setCalculadoEn(calculadoEn);
        item.setRecomendaciones(recomendaciones.stream().map(RecomendacionesUsuarioDynamoRepository::aItem).toList());
        // putItem reemplaza el item completo: el top anterior desaparece en la misma escritura.
        tabla.putItem(item);
    }

    @Override
    public Optional<List<ProductoEnriquecidoDTO>> obtener(String usuarioId) {
        RecomendacionesUsuarioItem item = tabla.getItem(Key.builder().partitionValue(usuarioId).build());
        if (item == null || item.getRecomendaciones() == null) {
            return Optional.empty();
        }
        return Optional.of(item.getRecomendaciones().stream()
                .map(RecomendacionesUsuarioDynamoRepository::aDto)
                .toList());
    }

    static RecomendacionesUsuarioItem.Recomendacion aItem(ProductoEnriquecidoDTO d) {
        RecomendacionesUsuarioItem.Recomendacion r = new RecomendacionesUsuarioItem.Recomendacion();
        r.setRankPosition(d.getRankPosition());
        r.setScore(d.getScore());
        r.setItemId(d.getItemId());
        r.setNombre(d.getNombre());
        r.setCategoria(d.getCategoria());
        r.setSubcategoria(d.getSubcategoria());
        r.setMarca(d.getMarca());
        r.setPrecio(d.getPrecio());
        r.setCalificacion(d.getCalificacion());
        r.setImagenUrl(d.getImagenUrl());
        r.setMotivo(d.getMotivo());
        r.setStock(d.getStock());
        return r;
    }

    static ProductoEnriquecidoDTO aDto(RecomendacionesUsuarioItem.Recomendacion r) {
        return ProductoEnriquecidoDTO.builder()
                .rankPosition(r.getRankPosition() != null ? r.getRankPosition() : 0)
                .score(r.getScore() != null ? r.getScore() : 0.0)
                .itemId(r.getItemId())
                .nombre(r.getNombre())
                .categoria(r.getCategoria())
                .subcategoria(r.getSubcategoria())
                .marca(r.getMarca())
                .precio(r.getPrecio() != null ? r.getPrecio() : 0.0)
                .calificacion(r.getCalificacion() != null ? r.getCalificacion() : 0.0)
                .imagenUrl(r.getImagenUrl())
                .motivo(r.getMotivo())
                .stock(r.getStock())
                .build();
    }
}
