package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.ProductoVistoDTO;
import com.kafka.recomendaciones.DTO.SnapshotMetricasDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Model.Producto;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Metricas en vivo con ventanas deslizantes en memoria, alimentadas por
 * grupo-dashboard (offset latest).
 *
 * Por que en memoria y no consultando DynamoDB: el tablero mira los ultimos
 * minutos y se refresca cada 2 s; una consulta por refresco seria costo y
 * latencia para datos que ya pasaron por este proceso. Al reiniciar se empieza
 * de cero, que es justo lo que pide "lo que pasa ahora" (por eso el grupo usa
 * latest).
 *
 * El instante que cuenta es el de RECEPCION (reloj del servicio), no el
 * timestamp del evento: el tablero mide el trafico que se observa ahora, y asi
 * un reproceso o un reloj de productor desfasado no ensucia las ventanas.
 */
@Service
public class MetricasEnVivo {

    static final long MINUTO_MS = 60_000L;
    static final long VENTANA_LARGA_MS = 15 * MINUTO_MS;
    static final long VENTANA_USUARIOS_MS = 5 * MINUTO_MS;
    static final int PUNTOS_SERIE = 15;
    static final int TOP = 5;
    /** Tope de memoria ante una rafaga enorme: se descartan los mas viejos. */
    static final int MAX_REGISTROS = 200_000;

    private record Registro(long instante, String usuarioId, TipoEvento tipo, String productoId) {
    }

    private final Clock reloj;
    private final CatalogoEnMemoria catalogo;
    private final Deque<Registro> registros = new ArrayDeque<>();
    private long total;

    public MetricasEnVivo(Clock reloj, CatalogoEnMemoria catalogo) {
        this.reloj = reloj;
        this.catalogo = catalogo;
    }

    /**
     * synchronized: escribe el hilo del listener y leen el scheduler del
     * WebSocket y los hilos HTTP; los volumenes de una demo hacen que el costo
     * del candado sea despreciable frente a la simplicidad.
     */
    public synchronized void registrar(EventoInteraccionDTO evento) {
        long ahora = reloj.millis();
        purgar(ahora);
        registros.addLast(new Registro(ahora, evento.getUsuarioId(), evento.getTipoEvento(), evento.getProductoId()));
        if (registros.size() > MAX_REGISTROS) {
            registros.removeFirst();
        }
        total++;
    }

    public synchronized SnapshotMetricasDTO snapshot() {
        long ahora = reloj.millis();
        purgar(ahora);

        long ultimoMinuto = 0;
        long[] serie = new long[PUNTOS_SERIE];
        long inicioSerie = ahora - PUNTOS_SERIE * MINUTO_MS;
        Set<String> usuarios = new HashSet<>();
        Map<String, Long> vistasPorProducto = new HashMap<>();
        Map<TipoEvento, Long> porTipo = new EnumMap<>(TipoEvento.class);
        for (TipoEvento t : TipoEvento.values()) {
            porTipo.put(t, 0L);
        }

        for (Registro r : registros) {
            long edad = ahora - r.instante();
            if (edad < MINUTO_MS) {
                ultimoMinuto++;
            }
            int indice = (int) ((r.instante() - inicioSerie) / MINUTO_MS);
            if (indice >= 0 && indice < PUNTOS_SERIE) {
                serie[indice]++;
            } else if (indice == PUNTOS_SERIE) {
                // El evento de este mismo milisegundo cae en el ultimo punto.
                serie[PUNTOS_SERIE - 1]++;
            }
            if (edad < VENTANA_USUARIOS_MS && r.usuarioId() != null) {
                usuarios.add(r.usuarioId());
            }
            if (r.tipo() != null) {
                porTipo.merge(r.tipo(), 1L, Long::sum);
                if (r.tipo() == TipoEvento.VER_PRODUCTO && r.productoId() != null) {
                    vistasPorProducto.merge(r.productoId(), 1L, Long::sum);
                }
            }
        }

        List<SnapshotMetricasDTO.PuntoSerie> puntos = new ArrayList<>();
        for (int i = 0; i < PUNTOS_SERIE; i++) {
            puntos.add(new SnapshotMetricasDTO.PuntoSerie(inicioSerie + i * MINUTO_MS, serie[i]));
        }

        List<SnapshotMetricasDTO.TopProducto> topProductos = vistasPorProducto.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP)
                .map(e -> {
                    Optional<Producto> p = catalogo.buscarPorId(e.getKey());
                    return new SnapshotMetricasDTO.TopProducto(e.getKey(),
                            p.map(Producto::getNombre).orElse(null),
                            p.map(Producto::getCategoria).orElse(null),
                            e.getValue());
                })
                .toList();

        List<ProductoVistoDTO> masVistos = topProductos.stream()
                .map(t -> new ProductoVistoDTO(t.getProductoId(), t.getNombre(), t.getCategoria(), t.getVistas()))
                .toList();

        Map<String, Long> porTipoTexto = new LinkedHashMap<>();
        porTipo.forEach((k, v) -> porTipoTexto.put(k.name(), v));

        return SnapshotMetricasDTO.builder()
                .timestamp(ahora)
                .interaccionesPorMinuto(ultimoMinuto)
                .serieMinutos(puntos)
                .topProductos(topProductos)
                .usuariosActivos(usuarios.size())
                .embudo(new SnapshotMetricasDTO.Embudo(
                        porTipo.get(TipoEvento.VER_PRODUCTO),
                        porTipo.get(TipoEvento.AGREGAR_CARRITO),
                        porTipo.get(TipoEvento.COMPRA)))
                .porTipo(porTipoTexto)
                .totalInteracciones(total)
                .productosMasVistos(masVistos)
                .build();
    }

    private void purgar(long ahora) {
        while (!registros.isEmpty() && ahora - registros.peekFirst().instante() >= VENTANA_LARGA_MS) {
            registros.removeFirst();
        }
    }
}
