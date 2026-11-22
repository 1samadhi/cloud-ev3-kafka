package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.ProductoEnriquecidoDTO;
import com.kafka.recomendaciones.Model.Producto;
import com.kafka.recomendaciones.Repository.HistorialEventosRepository;
import com.kafka.recomendaciones.Repository.RecomendacionesUsuarioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orquesta el flujo de grupo-recomendaciones: persiste el evento, recalcula el
 * top del usuario con el motor y lo guarda. El listener solo delega aca.
 *
 * Las excepciones de DynamoDB NO se atrapan: suben al contenedor de Kafka, que
 * reintenta con backoff y, si persisten, manda el evento al DLT. Atraparlas aca
 * haria que el offset avance con el evento perdido.
 */
@Slf4j
@Service
public class ServicioRecomendaciones {

    private final HistorialEventosRepository historial;
    private final RecomendacionesUsuarioRepository recomendaciones;
    private final CatalogoEnMemoria catalogo;
    private final MotorRecomendaciones motor;
    private final Clock reloj;
    private final int ventanaEventos;
    private final int top;

    /**
     * Popularidad global (suma de pesos por producto, de todos los usuarios). Vive
     * en memoria: es una senal secundaria del score (0.5) y se reconstruye con el
     * trafico; persistirla agregaria una escritura por evento sin cambiar el orden
     * de forma visible.
     */
    private final Map<String, Double> popularidad = new ConcurrentHashMap<>();

    public ServicioRecomendaciones(HistorialEventosRepository historial,
                                   RecomendacionesUsuarioRepository recomendaciones,
                                   CatalogoEnMemoria catalogo,
                                   MotorRecomendaciones motor,
                                   Clock reloj,
                                   @Value("${app.recomendaciones.ventana-eventos:100}") int ventanaEventos,
                                   @Value("${app.recomendaciones.top:5}") int top) {
        this.historial = historial;
        this.recomendaciones = recomendaciones;
        this.catalogo = catalogo;
        this.motor = motor;
        this.reloj = reloj;
        this.ventanaEventos = ventanaEventos;
        this.top = top;
    }

    /** Se recalcula en cada evento: la recomendacion refleja lo que el usuario hizo recien. */
    public List<ProductoEnriquecidoDTO> procesarEvento(EventoInteraccionDTO evento) {
        historial.guardar(evento);
        List<ProductoEnriquecidoDTO> nuevoTop = calcular(evento.getUsuarioId());
        recomendaciones.guardar(evento.getUsuarioId(), nuevoTop, reloj.millis());
        // Al final: si una escritura falla y el evento se reintenta, no se cuenta dos veces.
        sumarPopularidad(evento);
        log.info("Top {} recalculado para {} tras {} ({}): {}", nuevoTop.size(), evento.getUsuarioId(),
                evento.getTipoEvento(), evento.getProductoId(),
                nuevoTop.stream().map(ProductoEnriquecidoDTO::getItemId).toList());
        return nuevoTop;
    }

    /** Top calculado ahora con el historial persistido (ultimos N eventos). */
    public List<ProductoEnriquecidoDTO> calcular(String usuarioId) {
        List<EventoInteraccionDTO> recientes = historial.ultimos(usuarioId, ventanaEventos);
        List<MotorRecomendaciones.Recomendacion> resultado =
                motor.recomendar(recientes, catalogo.obtenerTodos(), Map.copyOf(popularidad), reloj.millis(), top);
        List<ProductoEnriquecidoDTO> salida = new ArrayList<>();
        int rank = 1;
        for (MotorRecomendaciones.Recomendacion r : resultado) {
            salida.add(enriquecer(r, rank++));
        }
        return salida;
    }

    /**
     * Lo que consulta la consola: el ultimo top guardado; si el usuario aun no
     * tiene, se calcula al vuelo (con historial vacio da "Popular entre otros
     * clientes") sin persistirlo, para no escribir en cada GET.
     */
    public List<ProductoEnriquecidoDTO> obtener(String usuarioId) {
        return recomendaciones.obtener(usuarioId).orElseGet(() -> calcular(usuarioId));
    }

    private void sumarPopularidad(EventoInteraccionDTO evento) {
        double w = MotorRecomendaciones.peso(evento.getTipoEvento());
        if (w <= 0) {
            return;
        }
        for (String productoId : MotorRecomendaciones.productosDe(evento)) {
            popularidad.merge(productoId, w, Double::sum);
        }
    }

    private static ProductoEnriquecidoDTO enriquecer(MotorRecomendaciones.Recomendacion r, int rank) {
        Producto p = r.producto();
        return ProductoEnriquecidoDTO.builder()
                .rankPosition(rank)
                .score(r.score())
                .itemId(p.getId())
                .nombre(p.getNombre())
                .categoria(p.getCategoria())
                .subcategoria(p.getSubcategoria())
                .marca(p.getMarca())
                .precio(p.getPrecio() != null ? p.getPrecio() : 0)
                .calificacion(p.getCalificacion() != null ? p.getCalificacion() : 0)
                .imagenUrl(p.getImagenUrl())
                .stock(p.getStock())
                .motivo(r.motivo())
                .build();
    }
}
