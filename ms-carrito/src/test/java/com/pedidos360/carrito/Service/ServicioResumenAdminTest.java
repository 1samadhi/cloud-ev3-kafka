package com.pedidos360.carrito.Service;

import com.pedidos360.carrito.DTO.ResumenAdminDTO;
import com.pedidos360.carrito.Model.EstadoPedido;
import com.pedidos360.carrito.Model.ItemPedido;
import com.pedidos360.carrito.Model.Pedido;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fija las cifras del panel admin. Antes cada una se calculaba sobre una base distinta: los
 * ingresos sumaban el total cobrado, las categorías el precio de lista, y sólo el gráfico por
 * día respetaba la ventana de 14 días. El panel mostraba entonces dos números que no
 * conciliaban, y nada lo detectaba.
 */
class ServicioResumenAdminTest {

    private static final ZoneId ZONA = ZoneId.of("America/Santiago");
    private static final LocalDate HOY = LocalDate.of(2026, 11, 23);

    /** Pedido pagado en una fecha dada, con los montos ya resueltos. */
    private static Pedido pedido(String id, LocalDate fecha, long subtotal, long descuento,
                                 long costoEnvio, EstadoPedido estado, ItemPedido... items) {
        Pedido p = new Pedido();
        p.setId(id);
        p.setUsuarioId("cliente@correo.cl");
        p.setEstado(estado);
        p.setSubtotal(subtotal);
        p.setDescuento(descuento);
        p.setCostoEnvio(costoEnvio);
        p.setTotal(subtotal - descuento + costoEnvio);
        Instant instante = fecha.atTime(12, 0).atZone(ZONA).toInstant();
        p.setCreadoEn(instante);
        p.setPagadoEn(instante);
        for (ItemPedido i : items) {
            p.agregarItem(i);
        }
        return p;
    }

    private static ItemPedido item(String productoId, String categoria, long precio, int cantidad) {
        return ItemPedido.builder()
                .productoId(productoId).nombre("Producto " + productoId)
                .precioUnitario(precio).cantidad(cantidad)
                .categoria(categoria).build();
    }

    private static long sumaCategorias(ResumenAdminDTO r) {
        return r.ventasPorCategoria().stream().mapToLong(ResumenAdminDTO.VentaCategoria::total).sum();
    }

    @Test
    @DisplayName("las categorías más los envíos dan exactamente los ingresos")
    void conciliacion() {
        var p = pedido("P-1", HOY, 300_000, 30_000, 5_000, EstadoPedido.PAGADO,
                item("item_1", "audio", 100_000, 2),
                item("item_2", "gaming", 100_000, 1));

        var r = ServicioResumenAdmin.calcular(List.of(p), HOY, ZONA);

        assertThat(r.ingresosTotales()).isEqualTo(275_000);   // 300.000 - 30.000 + 5.000
        assertThat(r.enviosTotales()).isEqualTo(5_000);
        assertThat(sumaCategorias(r) + r.enviosTotales()).isEqualTo(r.ingresosTotales());
    }

    @Test
    @DisplayName("el descuento se reparte entre las categorías según su peso")
    void descuentoProrrateado() {
        // 10 % de descuento: cada línea debe quedar con el 90 % de su precio de lista.
        var p = pedido("P-1", HOY, 300_000, 30_000, 0, EstadoPedido.PAGADO,
                item("item_1", "audio", 200_000, 1),
                item("item_2", "gaming", 100_000, 1));

        var r = ServicioResumenAdmin.calcular(List.of(p), HOY, ZONA);

        assertThat(r.ventasPorCategoria())
                .extracting(ResumenAdminDTO.VentaCategoria::categoria, ResumenAdminDTO.VentaCategoria::total)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("audio", 180_000L),
                        org.assertj.core.groups.Tuple.tuple("gaming", 90_000L));
        assertThat(sumaCategorias(r)).isEqualTo(270_000);     // subtotal - descuento
    }

    @Test
    @DisplayName("un pedido más viejo que la ventana no entra en ninguna cifra del período")
    void fueraDeLaVentana() {
        var dentro = pedido("P-1", HOY, 100_000, 0, 0, EstadoPedido.ENTREGADO,
                item("item_1", "audio", 100_000, 1));
        var fuera = pedido("P-2", HOY.minusDays(40), 900_000, 0, 0, EstadoPedido.ENTREGADO,
                item("item_2", "gaming", 900_000, 1));

        var r = ServicioResumenAdmin.calcular(List.of(dentro, fuera), HOY, ZONA);

        assertThat(r.ingresosTotales()).isEqualTo(100_000);
        assertThat(r.unidadesVendidas()).isEqualTo(1);
        assertThat(r.ventasPorCategoria()).extracting(ResumenAdminDTO.VentaCategoria::categoria)
                .containsExactly("audio");
        assertThat(r.masVendidos()).extracting(ResumenAdminDTO.ProductoVendido::productoId)
                .containsExactly("item_1");
    }

    @Test
    @DisplayName("un pedido sin pagar no cuenta como venta")
    void sinPagarNoCuenta() {
        var p = pedido("P-1", HOY, 100_000, 0, 0, EstadoPedido.PENDIENTE_PAGO,
                item("item_1", "audio", 100_000, 1));

        var r = ServicioResumenAdmin.calcular(List.of(p), HOY, ZONA);

        assertThat(r.ingresosTotales()).isZero();
        assertThat(r.ventasPorCategoria()).isEmpty();
    }

    @Test
    @DisplayName("el gráfico trae los 14 días, con ceros donde no hubo ventas")
    void catorceDiasSinHuecos() {
        var p = pedido("P-1", HOY.minusDays(3), 50_000, 0, 0, EstadoPedido.PAGADO,
                item("item_1", "audio", 50_000, 1));

        var r = ServicioResumenAdmin.calcular(List.of(p), HOY, ZONA);

        assertThat(r.ventasPorDia()).hasSize(14);
        assertThat(r.ventasPorDia().get(0).fecha()).isEqualTo(HOY.minusDays(13).toString());
        assertThat(r.ventasPorDia().get(13).fecha()).isEqualTo(HOY.toString());
        assertThat(r.ventasPorDia()).filteredOn(d -> d.total() > 0)
                .extracting(ResumenAdminDTO.VentaDia::fecha)
                .containsExactly(HOY.minusDays(3).toString());
    }

    @Test
    @DisplayName("el redondeo del prorrateo no pierde ni un peso")
    void redondeoExacto() {
        // Montos elegidos para que la división entera deje resto.
        var p = pedido("P-1", HOY, 100_000, 3_333, 0, EstadoPedido.PAGADO,
                item("item_1", "audio", 33_333, 1),
                item("item_2", "gaming", 33_333, 1),
                item("item_3", "laptops", 33_334, 1));

        var r = ServicioResumenAdmin.calcular(List.of(p), HOY, ZONA);

        assertThat(sumaCategorias(r)).isEqualTo(96_667);      // 100.000 - 3.333, al peso
        assertThat(sumaCategorias(r) + r.enviosTotales()).isEqualTo(r.ingresosTotales());
    }

    @Test
    @DisplayName("los pedidos activos se cuentan completos, sin recortar por fecha")
    void activosNoSeRecortan() {
        var viejoEnCurso = pedido("P-1", HOY.minusDays(40), 10_000, 0, 0, EstadoPedido.PREPARANDO,
                item("item_1", "audio", 10_000, 1));

        var r = ServicioResumenAdmin.calcular(List.of(viejoEnCurso), HOY, ZONA);

        assertThat(r.pedidosActivos()).isEqualTo(1);          // sigue en curso hoy
        assertThat(r.ingresosTotales()).isZero();             // pero no es venta del período
    }
}
