package com.pedidos360.carrito.Model;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Sucursales de retiro (§8). Lista cerrada: el cliente no puede inventar una. */
public enum Sucursal {
    SANTIAGO_CENTRO("Santiago Centro", "Huérfanos 1160"),
    PROVIDENCIA("Providencia", "Av. Providencia 2124"),
    MAIPU("Maipú", "Av. Pajaritos 3450");

    private final String nombre;
    private final String direccion;

    Sucursal(String nombre, String direccion) {
        this.nombre = nombre;
        this.direccion = direccion;
    }

    public String getNombre() { return nombre; }
    public String getDireccion() { return direccion; }

    /** Etiqueta canónica, la que se guarda en el carrito y en el destino del pedido. */
    public String getEtiqueta() { return nombre + " · " + direccion; }

    /**
     * Acepta la etiqueta completa, el nombre ("Maipú"/"maipu") o el código del enum, sin
     * distinguir mayúsculas ni tildes: la tienda puede mandar cualquiera de las tres formas.
     */
    public static Optional<Sucursal> buscar(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String buscado = normalizar(texto);
        return Arrays.stream(values())
                .filter(s -> buscado.equals(normalizar(s.getEtiqueta()))
                        || buscado.equals(normalizar(s.nombre))
                        || buscado.equals(normalizar(s.name())))
                .findFirst();
    }

    private static String normalizar(String s) {
        String sinTildes = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
