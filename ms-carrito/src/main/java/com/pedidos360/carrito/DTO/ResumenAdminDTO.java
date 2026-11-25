package com.pedidos360.carrito.DTO;

import java.util.List;

/**
 * GET /api/admin/resumen (§8). Sólo cuenta pedidos pagados en adelante, y sólo los de los
 * últimos 14 días: la etiqueta del panel dice «14 días» y las cifras lo respetan.
 *
 * Las cifras concilian entre sí:
 *
 * <pre>
 *   suma(ventasPorCategoria) + enviosTotales == ingresosTotales
 * </pre>
 *
 * Las categorías llevan lo cobrado por productos (con el descuento ya prorrateado por línea) y
 * el envío va aparte, porque no pertenece a ninguna categoría.
 */
public record ResumenAdminDTO(
        List<VentaDia> ventasPorDia,
        List<ProductoVendido> masVendidos,
        List<VentaCategoria> ventasPorCategoria,
        long pedidosActivos,
        long ingresosTotales,
        long unidadesVendidas,
        long enviosTotales) {

    /** fecha = yyyy-MM-dd en la zona de app.zona-horaria. */
    public record VentaDia(String fecha, long total) {
    }

    public record ProductoVendido(String productoId, String nombre, long unidades) {
    }

    /** total = lo cobrado por los productos de la categoría, con el descuento ya aplicado. */
    public record VentaCategoria(String categoria, long total) {
    }
}
