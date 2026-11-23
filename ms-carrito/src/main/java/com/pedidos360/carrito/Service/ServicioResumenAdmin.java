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
        List<Pedido> pagados = pedidos.stream().filter(p -> EstadoPedido.pagados().contains(p.getEstado())).toList();

        // Los 14 días aparecen siempre (con 0): el gráfico no debe tener huecos.
        Map<LocalDate, Long> porDia = new LinkedHashMap<>();
        for (int i = DIAS_VENTAS - 1; i >= 0; i--) {
            porDia.put(hoy.minusDays(i), 0L);
        }
        Map<String, Long> unidadesPorProducto = new HashMap<>();
        Map<String, String> nombrePorProducto = new HashMap<>();
        Map<String, Long> ventasPorCategoria = new HashMap<>();
        long ingresos = 0;
        long unidades = 0;
        long activos = 0;

        for (Pedido p : pagados) {
            var instante = p.getPagadoEn() != null ? p.getPagadoEn() : p.getCreadoEn();
            LocalDate fecha = instante.atZone(zona).toLocalDate();
            porDia.computeIfPresent(fecha, (f, acumulado) -> acumulado + p.getTotal());
            ingresos += p.getTotal();
            if (EstadoPedido.enCurso().contains(p.getEstado())) {
                activos++;
            }
            for (ItemPedido i : p.getItems()) {
                unidades += i.getCantidad();
                unidadesPorProducto.merge(i.getProductoId(), (long) i.getCantidad(), Long::sum);
                nombrePorProducto.put(i.getProductoId(), i.getNombre());
                String categoria = i.getCategoria() == null ? SIN_CATEGORIA : i.getCategoria();
                ventasPorCategoria.merge(categoria, i.getPrecioUnitario() * i.getCantidad(), Long::sum);
            }
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
        return new ResumenAdminDTO(ventasPorDia, masVendidos, categorias, activos, ingresos, unidades);
    }
}
