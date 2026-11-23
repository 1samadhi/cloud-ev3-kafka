package com.pedidos360.productos.Service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.pedidos360.productos.Model.Producto;

/**
 * Búsqueda y orden del catálogo. Clase pura (sin Spring ni base de datos) para poder
 * probar la regla de relevancia con JUnit simple.
 *
 * Se filtra en memoria porque el catálogo es chico (decenas de productos); si creciera a
 * miles, esto pasaría a una consulta con índice FULLTEXT sin cambiar el contrato REST.
 *
 * Relevancia: cada término de {@code q} debe aparecer en algún campo (AND); el puntaje
 * premia dónde aparece (nombre 10, marca 6, subcategoría/categoría 4, descripción 1).
 * Empates y búsquedas sin {@code q} se ordenan por calificación desc y luego id, para que
 * el orden sea estable entre llamadas (la tienda no "salta" al refrescar).
 */
public final class BuscadorCatalogo {

    private static final Pattern MARCAS_DIACRITICAS = Pattern.compile("\\p{M}+");

    private BuscadorCatalogo() {
    }

    public static List<Producto> filtrarYOrdenar(List<Producto> productos, String q, OrdenCatalogo orden) {
        String[] terminos = terminos(q);
        Comparator<Producto> desempate = Comparator
                .comparing((Producto p) -> p.getCalificacion() == null ? 0.0 : p.getCalificacion()).reversed()
                .thenComparing(Producto::getId);

        Comparator<Producto> comparador = switch (orden) {
            case PRECIO_ASC -> Comparator.comparing(Producto::getPrecio).thenComparing(Producto::getId);
            case PRECIO_DESC -> Comparator.comparing(Producto::getPrecio).reversed().thenComparing(Producto::getId);
            case RELEVANCIA -> Comparator.comparingInt((Producto p) -> puntaje(p, terminos)).reversed().thenComparing(desempate);
        };

        return productos.stream()
                .filter(p -> coincideTodos(p, terminos))
                .sorted(comparador)
                .toList();
    }

    /** Minúsculas y sin tildes: "camaras" encuentra "Cámaras" y "AUDIFONOS" encuentra "audifonos". */
    static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String sinTildes = MARCAS_DIACRITICAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
        return sinTildes.toLowerCase(Locale.ROOT).trim();
    }

    private static String[] terminos(String q) {
        String n = normalizar(q);
        if (n.isEmpty()) {
            return new String[0];
        }
        return Arrays.stream(n.split("\\s+")).filter(t -> !t.isBlank()).toArray(String[]::new);
    }

    private static boolean coincideTodos(Producto p, String[] terminos) {
        if (terminos.length == 0) {
            return true;
        }
        String todo = String.join(" ", Objects.toString(p.getNombre(), ""), Objects.toString(p.getMarca(), ""),
                Objects.toString(p.getCategoria(), ""), Objects.toString(p.getSubcategoria(), ""),
                Objects.toString(p.getDescripcion(), ""));
        String n = normalizar(todo);
        return Arrays.stream(terminos).allMatch(n::contains);
    }

    static int puntaje(Producto p, String[] terminos) {
        int total = 0;
        String nombre = normalizar(p.getNombre());
        String marca = normalizar(p.getMarca());
        String clasif = normalizar(p.getCategoria() + " " + p.getSubcategoria());
        String descripcion = normalizar(p.getDescripcion());
        for (String t : terminos) {
            if (nombre.contains(t)) {
                total += 10;
            }
            if (marca.contains(t)) {
                total += 6;
            }
            if (clasif.contains(t)) {
                total += 4;
            }
            if (descripcion.contains(t)) {
                total += 1;
            }
        }
        return total;
    }
}
