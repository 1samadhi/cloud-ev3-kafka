package com.pedidos360.carrito.DTO;

import java.util.List;

/** GET /api/admin/resumen (§8). Sólo cuenta pedidos pagados en adelante. */
public record ResumenAdminDTO(
        List<VentaDia> ventasPorDia,
        List<ProductoVendido> masVendidos,
        List<VentaCategoria> ventasPorCategoria,
        long pedidosActivos,
        long ingresosTotales,
        long unidadesVendidas) {

    /** fecha = yyyy-MM-dd en la zona de app.zona-horaria. */
    public record VentaDia(String fecha, long total) {
    }

    public record ProductoVendido(String productoId, String nombre, long unidades) {
    }

    public record VentaCategoria(String categoria, long total) {
    }
}
