package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.Config.CatalogoSemilla;
import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Model.Producto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Motor de recomendaciones por similitud (§5.2). Logica pura: no toca Kafka,
 * DynamoDB ni el reloj del sistema; todo entra por parametro. Por eso se prueba
 * con JUnit directo y el resultado es reproducible.
 *
 * Idea: con los eventos recientes del usuario se arma un perfil de afinidad por
 * categoria, subcategoria y marca; cada producto candidato se puntua por cuanto
 * se parece a ese perfil. Asi se recomienda lo PARECIDO a lo consultado, no lo
 * mismo que ya vio (el error del motor anterior).
 */
@Component
public class MotorRecomendaciones {

    static final double PESO_CATEGORIA = 3.0;
    static final double PESO_SUBCATEGORIA = 2.0;
    static final double PESO_MARCA = 1.0;
    static final double PESO_POPULARIDAD = 0.5;
    /** Lo ya visto pero no comprado entra con -20 %: se prefiere lo parecido a lo repetido. */
    static final double FACTOR_YA_VISTO = 0.8;
    /** Vida media del decaimiento: un evento de hace 24 h pesa la mitad. */
    static final double VIDA_MEDIA_HORAS = 24.0;

    public static final String MOTIVO_POPULAR = "Popular entre otros clientes";

    /** Resultado del motor: el producto, su puntaje y por que se recomienda. */
    public record Recomendacion(Producto producto, double score, String motivo) {
    }

    /**
     * Peso comercial de cada tipo: comprar demuestra mas interes que agregar al
     * carrito, y eso mas que mirar. Gestionar el envio no dice nada del gusto.
     */
    public static double peso(TipoEvento tipo) {
        if (tipo == null) {
            return 0;
        }
        return switch (tipo) {
            case VER_PRODUCTO -> 1.0;
            case APLICAR_CUPON -> 0.5;
            case GESTIONAR_ENVIO -> 0.0;
            case AGREGAR_CARRITO -> 3.0;
            case COMPRA -> 5.0;
        };
    }

    /** 0.5^(edadHoras/24): lo reciente pesa mas; la sesion actual manda sobre la de ayer. */
    static double decaimiento(long edadMs) {
        double horas = Math.max(0, edadMs) / 3_600_000.0;
        return Math.pow(0.5, horas / VIDA_MEDIA_HORAS);
    }

    /**
     * @param historial    eventos recientes del usuario (la ventana ya viene recortada)
     * @param catalogo     productos disponibles
     * @param popularidad  puntaje global por productoId (de todos los usuarios)
     * @param ahora        epoch millis del calculo
     * @param top          cuantos devolver
     */
    public List<Recomendacion> recomendar(List<EventoInteraccionDTO> historial, Collection<Producto> catalogo,
                                          Map<String, Double> popularidad, long ahora, int top) {
        Map<String, Producto> porId = catalogo.stream()
                .collect(Collectors.toMap(Producto::getId, Function.identity(), (a, b) -> b));
        Perfil perfil = construirPerfil(historial, porId, ahora);

        double maxPopularidad = popularidad.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);

        List<Recomendacion> candidatos = new ArrayList<>();
        for (Producto p : catalogo) {
            // Regla de negocio: no se recomienda lo ya comprado ni lo que no se puede vender.
            if (perfil.comprados.contains(p.getId()) || !p.tieneStock()) {
                continue;
            }
            double afCategoria = perfil.afinidad(perfil.categoria, p.getCategoria());
            double afSubcategoria = perfil.afinidad(perfil.subcategoria, p.getSubcategoria());
            double afMarca = perfil.afinidad(perfil.marca, p.getMarca());
            double pop = maxPopularidad > 0 ? popularidad.getOrDefault(p.getId(), 0.0) / maxPopularidad : 0;

            double score = PESO_CATEGORIA * afCategoria
                    + PESO_SUBCATEGORIA * afSubcategoria
                    + PESO_MARCA * afMarca
                    + PESO_POPULARIDAD * pop;
            if (perfil.interactuados.contains(p.getId())) {
                score *= FACTOR_YA_VISTO;
            }
            candidatos.add(new Recomendacion(p, redondear(score), motivo(p, perfil, porId, afCategoria, afMarca)));
        }

        // Empates (sin historial ni popularidad): mejor calificados primero, luego
        // por id para que el orden sea estable entre llamadas.
        candidatos.sort(Comparator.comparingDouble(Recomendacion::score).reversed()
                .thenComparingDouble(r -> -valor(r.producto().getCalificacion()))
                .thenComparing(r -> r.producto().getId()));
        return candidatos.stream().limit(top).toList();
    }

    private String motivo(Producto candidato, Perfil perfil, Map<String, Producto> porId,
                          double afCategoria, double afMarca) {
        if (perfil.total <= 0) {
            return MOTIVO_POPULAR;
        }
        // 1) El parecido mas fuerte: un producto concreto que el usuario miro (o
        //    compro) de la MISMA subcategoria. Es la explicacion mas concreta.
        String ancla = null;
        double pesoAncla = 0;
        for (Map.Entry<String, Double> e : perfil.pesoPorProducto.entrySet()) {
            Producto visto = porId.get(e.getKey());
            if (visto == null || visto.getId().equals(candidato.getId())
                    || visto.getSubcategoria() == null
                    || !visto.getSubcategoria().equals(candidato.getSubcategoria())) {
                continue;
            }
            if (e.getValue() > pesoAncla) {
                pesoAncla = e.getValue();
                ancla = visto.getId();
            }
        }
        if (ancla != null) {
            String verbo = perfil.comprados.contains(ancla) ? "compraste" : "viste";
            return "Porque " + verbo + " " + porId.get(ancla).getNombre();
        }
        // 2) Afinidad por categoria, con el porcentaje del perfil que representa.
        if (afCategoria > 0) {
            return "Te interesa " + CatalogoSemilla.etiquetaCategoria(candidato.getCategoria())
                    + " (" + Math.round(afCategoria * 100) + " %)";
        }
        // 3) Solo coincide la marca.
        if (afMarca > 0) {
            return "Marca que sueles revisar: " + candidato.getMarca();
        }
        return MOTIVO_POPULAR;
    }

    private Perfil construirPerfil(List<EventoInteraccionDTO> historial, Map<String, Producto> porId, long ahora) {
        Perfil perfil = new Perfil();
        for (EventoInteraccionDTO ev : historial) {
            double w = peso(ev.getTipoEvento()) * decaimiento(ahora - ev.getTimestamp());
            if (w <= 0) {
                continue;
            }
            for (String productoId : productosDe(ev)) {
                Producto p = porId.get(productoId);
                // El catalogo es la fuente de verdad de los atributos; los del evento
                // solo se usan si el producto no esta (p. ej. ya se elimino).
                String categoria = p != null ? p.getCategoria() : ev.getCategoria();
                String subcategoria = p != null ? p.getSubcategoria() : ev.getSubcategoria();
                String marca = p != null ? p.getMarca() : ev.getMarca();

                perfil.total += w;
                sumar(perfil.categoria, categoria, w);
                sumar(perfil.subcategoria, subcategoria, w);
                sumar(perfil.marca, marca, w);
                perfil.pesoPorProducto.merge(productoId, w, Double::sum);
                if (ev.getTipoEvento() == TipoEvento.COMPRA) {
                    perfil.comprados.add(productoId);
                } else {
                    perfil.interactuados.add(productoId);
                }
            }
        }
        return perfil;
    }

    /**
     * APLICAR_CUPON no trae productoId: si en detalle.productos vienen los del
     * carrito (ms-carrito los manda separados por coma) el 0.5 se aplica a cada uno.
     */
    static List<String> productosDe(EventoInteraccionDTO ev) {
        if (ev.getTipoEvento() == TipoEvento.APLICAR_CUPON) {
            String lista = ev.getDetalle() != null ? ev.getDetalle().get("productos") : null;
            if (lista == null || lista.isBlank()) {
                return List.of();
            }
            return Arrays.stream(lista.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        return ev.getProductoId() == null || ev.getProductoId().isBlank() ? List.of() : List.of(ev.getProductoId());
    }

    private static void sumar(Map<String, Double> mapa, String clave, double w) {
        if (clave != null && !clave.isBlank()) {
            mapa.merge(clave, w, Double::sum);
        }
    }

    private static double valor(Double d) {
        return d == null ? 0 : d;
    }

    private static double redondear(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** Perfil del usuario: sumas de pesos por atributo, normalizadas al consultar. */
    private static final class Perfil {
        double total;
        final Map<String, Double> categoria = new HashMap<>();
        final Map<String, Double> subcategoria = new HashMap<>();
        final Map<String, Double> marca = new HashMap<>();
        final Map<String, Double> pesoPorProducto = new HashMap<>();
        final Set<String> comprados = new HashSet<>();
        final Set<String> interactuados = new HashSet<>();

        /** Fraccion del interes total que cae en ese valor: 0..1, comparable entre usuarios. */
        double afinidad(Map<String, Double> mapa, String clave) {
            if (total <= 0 || clave == null) {
                return 0;
            }
            return mapa.getOrDefault(clave, 0.0) / total;
        }
    }
}
