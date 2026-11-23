package com.pedidos360.productos.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pedidos360.productos.Component.ProductorInteracciones;
import com.pedidos360.productos.DTO.CategoriaDTO;
import com.pedidos360.productos.DTO.EventoInteraccionDTO;
import com.pedidos360.productos.DTO.NivelStock;
import com.pedidos360.productos.DTO.ProductoCatalogoDTO;
import com.pedidos360.productos.DTO.ProductoDTO;
import com.pedidos360.productos.DTO.StockAdminDTO;
import com.pedidos360.productos.DTO.TipoEvento;
import com.pedidos360.productos.Exception.ProductoNoEncontradoException;
import com.pedidos360.productos.Model.Producto;
import com.pedidos360.productos.Repository.CategoriaRepositorio;
import com.pedidos360.productos.Repository.ProductoRepositorio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Lecturas del catálogo y emisión de VER_PRODUCTO. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServicioCatalogo {

    static final String ORIGEN = "ms-productos";

    private final ProductoRepositorio productos;
    private final CategoriaRepositorio categorias;
    private final MapeadorProducto mapeador;
    private final ProductorInteracciones productorInteracciones;

    @Value("${app.stock.umbral-bajo:10}")
    private int umbralBajo;

    @Transactional(readOnly = true)
    public List<ProductoDTO> listar(String categoria, String q, OrdenCatalogo orden) {
        List<Producto> base = (categoria == null || categoria.isBlank())
                ? productos.findAllByOrderByIdAsc()
                : productos.findByCategoriaOrderByIdAsc(categoria.trim().toLowerCase());
        Map<String, String> etiquetas = mapeador.etiquetas();
        return BuscadorCatalogo.filtrarYOrdenar(base, q, orden).stream()
                .map(p -> mapeador.aDTO(p, etiquetas))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CategoriaDTO> categorias() {
        Map<String, Long> conteo = new HashMap<>();
        for (Object[] fila : productos.contarPorCategoria()) {
            conteo.put((String) fila[0], (Long) fila[1]);
        }
        return categorias.findAllByOrderByOrdenAsc().stream()
                .map(c -> new CategoriaDTO(c.getSlug(), c.getEtiqueta(), conteo.getOrDefault(c.getSlug(), 0L)))
                .toList();
    }

    /**
     * Detalle de producto. Si viene usuario se publica VER_PRODUCTO.
     *
     * Deliberadamente SIN transacción alrededor: la publicación en Kafka puede bloquear
     * hasta max.block.ms si el clúster está caído, y no tiene sentido retener una conexión
     * del pool de la base mientras tanto. La lectura usa la transacción propia de findById.
     */
    public ProductoDTO obtenerDetalle(String id, Optional<String> usuarioId) {
        Producto p = productos.findById(id).orElseThrow(() -> new ProductoNoEncontradoException(id));
        usuarioId.ifPresent(u -> productorInteracciones.publicar(eventoVerProducto(u, p)));
        return mapeador.aDTO(p);
    }

    static EventoInteraccionDTO eventoVerProducto(String usuarioId, Producto p) {
        return EventoInteraccionDTO.builder()
                .idEvento(UUID.randomUUID().toString())
                .usuarioId(usuarioId)
                .tipoEvento(TipoEvento.VER_PRODUCTO)
                .productoId(p.getId())
                .categoria(p.getCategoria())
                .subcategoria(p.getSubcategoria())
                .marca(p.getMarca())
                .origen(ORIGEN)
                // El nombre viaja en detalle para que el dashboard pueda mostrar "Galaxy S24"
                // aunque su catálogo en memoria todavía no se haya reconstruido.
                .detalle(Map.of("nombre", p.getNombre()))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /** Panel admin: lo más urgente primero (menor stock arriba). */
    @Transactional(readOnly = true)
    public List<StockAdminDTO> stockAdmin() {
        return productos.findAll().stream()
                .map(p -> new StockAdminDTO(p.getId(), p.getNombre(), p.getStock(), NivelStock.clasificar(p.getStock(), umbralBajo)))
                .sorted(Comparator.comparingInt(StockAdminDTO::stock).thenComparing(StockAdminDTO::id))
                .toList();
    }

    /** Snapshots de TODO el catálogo (publicación inicial en el tópico compactado). */
    @Transactional(readOnly = true)
    public List<ProductoCatalogoDTO> snapshotsCatalogo() {
        return productos.findAllByOrderByIdAsc().stream().map(MapeadorProducto::aSnapshot).toList();
    }
}
