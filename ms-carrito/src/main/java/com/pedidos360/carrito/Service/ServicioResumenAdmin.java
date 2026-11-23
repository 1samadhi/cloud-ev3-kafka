package com.pedidos360.carrito.Service;

import com.pedidos360.carrito.DTO.ResumenAdminDTO;
import com.pedidos360.carrito.Model.EstadoPedido;
import com.pedidos360.carrito.Model.ItemPedido;
import com.pedidos360.carrito.Model.Pedido;
import com.pedidos360.carrito.Repository.PedidoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resumen para el panel admin de la tienda. Se calcula en memoria sobre los pedidos pagados:
 * con el volumen de la demo es más simple y portable (H2/MySQL) que consultas de agregación; si
 * creciera, se movería a consultas GROUP BY o a una tabla de agregados.
 *
 * <h2>Las cifras cuadran entre sí, y por qué eso costaba</h2>
 *
 * Antes cada cifra se calculaba sobre una base distinta, así que el panel mostraba dos números
 * que no conciliaban:
 *
 * <ul>
 *   <li>los ingresos sumaban el <b>total cobrado</b> (con descuento y envío),</li>
 *   <li>las categorías sumaban <b>unidades × precio de lista</b>, ignorando el descuento,</li>
 *   <li>y sólo el gráfico por día respetaba la ventana de 14 días: ingresos, categorías y más
 *       vendidos sumaban <b>todo el historial</b>, aunque la etiqueta dijera «14 días».</li>
 * </ul>
 *
 * Ahora las cuatro cifras salen de los mismos pedidos —los pagados dentro de la ventana— y el
 * descuento se <b>prorratea por línea</b> según su peso en el subtotal, de modo que la suma de
 * las categorías es exactamente lo cobrado por productos. El envío se informa aparte porque no
 * pertenece a ninguna categoría, y así la conciliación es explícita:
 *
 * <pre>
 *   suma(ventasPorCategoria) + enviosTotales == ingresosTotales
 * </pre>
 *
 * {@code ServicioResumenAdminTest} fija esa identidad para que no se vuelva a romper en silencio.
 */
@Service
public class ServicioResumenAdmin {

    static final int DIAS_VENTAS = 14;
    static final int TOP_MAS_VENDIDOS = 5;
    static final String SIN_CATEGORIA = "sin-categoria";

    private final PedidoRepository pedidoRepository;
    private final Clock reloj;
    private final ZoneId zona;

    public ServicioResumenAdmin(PedidoRepository pedidoRepository, Clock reloj,
                                @Value("${app.zona-horaria:America/Santiago}") String zona) {
        this.pedidoRepository = pedidoRepository;
        this.reloj = reloj;
        this.zona = ZoneId.of(zona);
    }

    @Transactional(readOnly = true)
    public ResumenAdminDTO resumen() {
        List<Pedido> pagados = pedidoRepository.findByEstadoIn(EstadoPedido.pagados());
        return calcular(pagados, LocalDate.now(reloj.withZone(zona)), zona);
    }

    /** Puro: recibe sólo pedidos pagados en adelante (se vuelve a filtrar por seguridad). */
    static ResumenAdminDTO calcular(List<Pedido> pedidos, LocalDate hoy, ZoneId zona) {
        LocalDate desde = hoy.minusDays(DIAS_VENTAS - 1L);

        // Los 14 días aparecen siempre (con 0): el gráfico no debe tener huecos.
        Map<LocalDate, Long> porDia = new LinkedHashMap<>();
        for (int i = DIAS_VENTAS - 1; i >= 0; i--) {
            porDia.put(hoy.minusDays(i), 0L);
        }
        Map<String, Long> unidadesPorProducto = new HashMap<>();
        Map<String, String> nombrePorProducto = new HashMap<>();
        Map<String, Long> ventasPorCategoria = new HashMap<>();
        long ingresos = 0;
        long envios = 0;
        long unidades = 0;
        long activos = 0;

        for (Pedido p : pedidos) {
            if (!EstadoPedido.pagados().contains(p.getEstado())) {
                continue;
            }
            // Los pedidos en curso se cuentan completos: es un estado actual, no una venta
            // del período, así que no corresponde recortarlo por fecha.
            if (EstadoPedido.enCurso().contains(p.getEstado())) {
                activos++;
            }

            var instante = p.getPagadoEn() != null ? p.getPagadoEn() : p.getCreadoEn();
            LocalDate fecha = instante.atZone(zona).toLocalDate();
            if (fecha.isBefore(desde) || fecha.isAfter(hoy)) {
                continue;   // fuera de la ventana: no entra en ninguna cifra del período
            }

            porDia.merge(fecha, p.getTotal(), Long::sum);
            ingresos += p.getTotal();
            envios += p.getCostoEnvio();

            for (ItemPedido i : p.getItems()) {
                unidades += i.getCantidad();
                unidadesPorProducto.merge(i.getProductoId(), (long) i.getCantidad(), Long::sum);
                nombrePorProducto.put(i.getProductoId(), i.getNombre());
                String categoria = i.getCategoria() == null ? SIN_CATEGORIA : i.getCategoria();
                ventasPorCategoria.merge(categoria, cobradoPorLinea(p, i), Long::sum);
            }
            // El prorrateo redondea hacia abajo por línea, así que puede faltar algún peso
            // respecto de (subtotal - descuento). Se ajusta en la categoría de la última
            // línea para que la identidad de conciliación se cumpla al peso.
            ajustarRedondeo(p, ventasPorCategoria);
        }

        var ventasPorDia = porDia.entrySet().stream()
                .map(e -> new ResumenAdminDTO.VentaDia(e.getKey().toString(), e.getValue()))
                .toList();
        var masVendidos = unidadesPorProducto.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_MAS_VENDIDOS)
                .map(e -> new ResumenAdminDTO.ProductoVendido(e.getKey(), nombrePorProducto.get(e.getKey()), e.getValue()))
                .toList();
        var categorias = ventasPorCategoria.entrySet().stream()
                .map(e -> new ResumenAdminDTO.VentaCategoria(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(ResumenAdminDTO.VentaCategoria::total).reversed()
                        .thenComparing(ResumenAdminDTO.VentaCategoria::categoria))
                .toList();
        return new ResumenAdminDTO(ventasPorDia, masVendidos, categorias, activos, ingresos,
                unidades, envios);
    }

    /**
     * Lo efectivamente cobrado por una línea: su precio de lista menos la parte del descuento
     * que le corresponde, proporcional a su peso en el subtotal del pedido.
     *
     * Se usa {@code Math.multiplyHigh}-free aritmética con long y división entera: los montos son
     * pesos chilenos (sin decimales) y el subtotal acota el numerador, así que no desborda.
     */
    private static long cobradoPorLinea(Pedido p, ItemPedido i) {
        long bruto = i.getPrecioUnitario() * i.getCantidad();
        if (p.getDescuento() <= 0 || p.getSubtotal() <= 0) {
            return bruto;
        }
        long parteDescuento = (p.getDescuento() * bruto) / p.getSubtotal();
        return bruto - parteDescuento;
    }

    /**
     * Reparte entre las categorías el resto que deja la división entera, para que la suma por
     * categorías sea exactamente {@code subtotal - descuento}.
     */
    private static void ajustarRedondeo(Pedido p, Map<String, Long> ventasPorCategoria) {
        if (p.getDescuento() <= 0 || p.getSubtotal() <= 0 || p.getItems().isEmpty()) {
            return;
        }
        long esperado = p.getSubtotal() - p.getDescuento();
        long sumado = 0;
        for (ItemPedido i : p.getItems()) {
            sumado += cobradoPorLinea(p, i);
        }
        long resto = esperado - sumado;
        if (resto == 0) {
            return;
        }
        ItemPedido ultimo = p.getItems().get(p.getItems().size() - 1);
        String categoria = ultimo.getCategoria() == null ? SIN_CATEGORIA : ultimo.getCategoria();
        ventasPorCategoria.merge(categoria, resto, Long::sum);
    }
}
