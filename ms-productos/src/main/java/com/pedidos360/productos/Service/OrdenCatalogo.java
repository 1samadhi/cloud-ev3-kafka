package com.pedidos360.productos.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

import com.pedidos360.productos.Exception.PeticionInvalidaException;

/** Valores aceptados por {@code ?orden=} en el listado (§7.2). */
public enum OrdenCatalogo {
    RELEVANCIA("relevancia"),
    PRECIO_ASC("precio_asc"),
    PRECIO_DESC("precio_desc");

    private final String valor;

    OrdenCatalogo(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }

    /** Vacío o ausente = relevancia; cualquier otro valor desconocido es un 400, no un default silencioso. */
    public static OrdenCatalogo desde(String texto) {
        if (texto == null || texto.isBlank()) {
            return RELEVANCIA;
        }
        String t = texto.trim().toLowerCase();
        return Arrays.stream(values()).filter(o -> o.valor.equals(t)).findFirst()
                .orElseThrow(() -> new PeticionInvalidaException("orden desconocido '" + texto + "'; valores válidos: "
                        + Arrays.stream(values()).map(OrdenCatalogo::valor).collect(Collectors.joining(", "))));
    }
}
