package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.Config.CatalogoSemilla;
import com.kafka.recomendaciones.Model.Producto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catalogo en memoria alimentado por el topico compactado catalogo-productos.
 *
 * Por que en memoria y no consultando ms-productos por REST: el motor recorre el
 * catalogo completo en cada evento consumido; una llamada HTTP por evento
 * acoplaria la disponibilidad de las recomendaciones a la de ms-productos. Con el
 * topico compactado el estado se reconstruye leyendo desde el inicio y queda
 * local.
 *
 * Mientras el topico no haya entregado ningun producto se usa la semilla de §7.1
 * como respaldo; en cuanto llega el primero, manda el topico (incluidos los
 * borrados por tombstone).
 */
@Slf4j
@Service
public class CatalogoEnMemoria {

    private final Map<String, Producto> desdeTopico = new ConcurrentHashMap<>();
    private final Map<String, Producto> semilla;

    public CatalogoEnMemoria(CatalogoSemilla catalogoSemilla) {
        Map<String, Producto> mapa = new LinkedHashMap<>();
        catalogoSemilla.productos().forEach(p -> mapa.put(p.getId(), p));
        this.semilla = Map.copyOf(mapa);
    }

    /**
     * Aplica un snapshot. Si llega uno MAS VIEJO que el guardado (reproceso,
     * reinicio del grupo) se ignora: el catalogo nunca retrocede.
     */
    public void actualizar(Producto producto) {
        desdeTopico.merge(producto.getId(), producto, (actual, nuevo) -> {
            if (nuevo.getActualizadoEn() < actual.getActualizadoEn()) {
                log.info("Snapshot viejo de {} ignorado ({} < {})", nuevo.getId(),
                        nuevo.getActualizadoEn(), actual.getActualizadoEn());
                return actual;
            }
            return nuevo;
        });
    }

    /** Tombstone del topico compactado: el producto ya no existe. */
    public void eliminar(String productoId) {
        desdeTopico.remove(productoId);
    }

    public Collection<Producto> obtenerTodos() {
        return usandoSemilla() ? semilla.values() : List.copyOf(desdeTopico.values());
    }

    public Optional<Producto> buscarPorId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(usandoSemilla() ? semilla.get(id) : desdeTopico.get(id));
    }

    public boolean usandoSemilla() {
        return desdeTopico.isEmpty();
    }

    public int tamano() {
        return obtenerTodos().size();
    }
}
