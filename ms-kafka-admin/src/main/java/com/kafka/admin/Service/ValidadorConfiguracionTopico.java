package com.kafka.admin.Service;

import com.kafka.admin.Exception.SolicitudInvalidaException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lista blanca y tipado de las configuraciones de tópico que la API deja tocar.
 *
 * Por qué una lista blanca y no "lo que Kafka acepte": Kafka acepta cosas que
 * rompen el proyecto sin avisar (unclean.leader.election.enable=true pierde
 * mensajes confirmados; message.timestamp.type cambia la semántica de las
 * ventanas). Sólo se exponen las perillas que el criterio 7 pide ajustar por
 * flujo: retención, limpieza, durabilidad, segmentos y compresión.
 *
 * Clase pura (sin AdminClient) para poder probar cada regla con JUnit sin
 * levantar nada; el servicio le pasa el factor de replicación del tópico.
 */
@Component
public class ValidadorConfiguracionTopico {

    public static final Set<String> LISTA_BLANCA = Set.copyOf(List.of(
            "retention.ms", "retention.bytes", "cleanup.policy", "min.insync.replicas",
            "segment.bytes", "segment.ms", "compression.type", "max.message.bytes",
            "min.compaction.lag.ms", "min.cleanable.dirty.ratio"));

    /** Las tres combinaciones válidas; "compact,delete" compacta y además vence por tiempo. */
    public static final Set<String> POLITICAS_LIMPIEZA = Set.of("delete", "compact", "compact,delete");

    /** "producer" = conservar el códec con que llegó el lote (no recomprimir en el broker). */
    public static final Set<String> COMPRESIONES = Set.of("uncompressed", "zstd", "lz4", "snappy", "gzip", "producer");

    /*
     * Piso propio para segment.bytes. Kafka admite hasta 14 bytes, pero
     * segmentos diminutos multiplican archivos abiertos e índices en cada
     * broker; 1 MiB deja margen para demostrar compactación sin dañar el clúster.
     */
    static final long SEGMENT_BYTES_MINIMO = 1_048_576L;

    public boolean esEditable(String clave) {
        return LISTA_BLANCA.contains(clave);
    }

    /**
     * Valida claves y valores a establecer (SET) y devuelve los valores
     * normalizados (sin espacios; "compact, delete" queda "compact,delete").
     * Reúne TODOS los errores antes de fallar: corregir de a uno por petición
     * es una mala experiencia en la consola.
     *
     * @param factorReplicacion RF del tópico; min.insync.replicas no puede superarlo
     *                          porque con acks=all ningún productor podría escribir.
     */
    public Map<String, String> validarEstablecer(Map<String, String> valores, int factorReplicacion) {
        Map<String, String> normalizados = new LinkedHashMap<>();
        List<String> errores = new ArrayList<>();
        if (valores == null) {
            return normalizados;
        }
        for (Map.Entry<String, String> e : valores.entrySet()) {
            String clave = e.getKey() == null ? "" : e.getKey().trim();
            String valor = e.getValue() == null ? null : e.getValue().replace(" ", "");
            if (!LISTA_BLANCA.contains(clave)) {
                errores.add("'" + clave + "' no está en la lista blanca");
                continue;
            }
            if (valor == null || valor.isEmpty()) {
                errores.add(clave + ": el valor no puede estar vacío (para volver al valor por defecto use 'restablecer')");
                continue;
            }
            String error = validarValor(clave, valor, factorReplicacion);
            if (error != null) {
                errores.add(clave + ": " + error);
            } else {
                normalizados.put(clave, valor);
            }
        }
        lanzarSiHayErrores(errores);
        return normalizados;
    }

    /** Valida las claves a restablecer (DELETE): sólo las de la lista blanca, sin repetir. */
    public void validarRestablecer(Collection<String> claves) {
        if (claves == null) {
            return;
        }
        List<String> errores = new ArrayList<>();
        Set<String> vistas = new LinkedHashSet<>();
        for (String clave : claves) {
            String c = clave == null ? "" : clave.trim();
            if (!LISTA_BLANCA.contains(c)) {
                errores.add("'" + c + "' no está en la lista blanca");
            } else if (!vistas.add(c)) {
                errores.add("'" + c + "' está repetida en 'restablecer'");
            }
        }
        lanzarSiHayErrores(errores);
    }

    private String validarValor(String clave, String valor, int factorReplicacion) {
        switch (clave) {
            case "retention.ms":
            case "retention.bytes":
                // -1 = sin límite (catalogo-productos lo usa: retención infinita).
                return enteroLargoEnRango(valor, -1, Long.MAX_VALUE, "un entero >= -1 (-1 = sin límite)");
            case "segment.ms":
                return enteroLargoEnRango(valor, 1, Long.MAX_VALUE, "un entero >= 1 (milisegundos)");
            case "min.compaction.lag.ms":
                return enteroLargoEnRango(valor, 0, Long.MAX_VALUE, "un entero >= 0 (milisegundos)");
            case "segment.bytes":
                // Kafka lo guarda como int: más de 2^31-1 lo rechazaría el broker.
                return enteroLargoEnRango(valor, SEGMENT_BYTES_MINIMO, Integer.MAX_VALUE,
                        "un entero entre " + SEGMENT_BYTES_MINIMO + " (1 MiB) y " + Integer.MAX_VALUE);
            case "max.message.bytes":
                return enteroLargoEnRango(valor, 1, Integer.MAX_VALUE, "un entero entre 1 y " + Integer.MAX_VALUE);
            case "min.insync.replicas":
                return enteroLargoEnRango(valor, 1, Math.max(1, factorReplicacion),
                        "un entero entre 1 y el factor de replicación del tópico (" + factorReplicacion
                                + "); con más, ninguna escritura con acks=all se podría confirmar");
            case "cleanup.policy":
                return POLITICAS_LIMPIEZA.contains(valor) ? null
                        : "debe ser uno de " + POLITICAS_LIMPIEZA + " (recibido '" + valor + "')";
            case "compression.type":
                return COMPRESIONES.contains(valor) ? null
                        : "debe ser uno de " + COMPRESIONES + " (recibido '" + valor + "')";
            case "min.cleanable.dirty.ratio":
                try {
                    double d = Double.parseDouble(valor);
                    return d >= 0.0 && d <= 1.0 ? null : "debe ser un decimal entre 0 y 1";
                } catch (NumberFormatException ex) {
                    return "debe ser un decimal entre 0 y 1 (recibido '" + valor + "')";
                }
            default:
                return "no está en la lista blanca";
        }
    }

    private static String enteroLargoEnRango(String valor, long min, long max, String esperado) {
        try {
            long n = Long.parseLong(valor);
            return n >= min && n <= max ? null : "debe ser " + esperado + " (recibido " + n + ")";
        } catch (NumberFormatException ex) {
            return "debe ser " + esperado + " (recibido '" + valor + "')";
        }
    }

    private static void lanzarSiHayErrores(List<String> errores) {
        if (!errores.isEmpty()) {
            throw new SolicitudInvalidaException("Configuración inválida: " + String.join("; ", errores)
                    + ". Claves permitidas: " + new java.util.TreeSet<>(LISTA_BLANCA));
        }
    }
}
