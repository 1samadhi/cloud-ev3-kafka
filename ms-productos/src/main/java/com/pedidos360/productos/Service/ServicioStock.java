package com.pedidos360.productos.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pedidos360.productos.DTO.FaltanteDTO;
import com.pedidos360.productos.DTO.ItemReservaDTO;
import com.pedidos360.productos.DTO.PeticionReservaDTO;
import com.pedidos360.productos.DTO.ProductoDTO;
import com.pedidos360.productos.DTO.RespuestaReservaDTO;
import com.pedidos360.productos.Exception.ConflictoReservaException;
import com.pedidos360.productos.Exception.ProductoNoEncontradoException;
import com.pedidos360.productos.Exception.StockInsuficienteException;
import com.pedidos360.productos.Model.EstadoReserva;
import com.pedidos360.productos.Model.Producto;
import com.pedidos360.productos.Model.ReservaStock;
import com.pedidos360.productos.Repository.ProductoRepositorio;
import com.pedidos360.productos.Repository.ReservaStockRepositorio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reservas, liberaciones y reposiciones de stock: los comandos transaccionales que
 * ms-carrito invoca por REST (regla de oro: REST para comandos, Kafka para eventos).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServicioStock {

    public static final String RESERVADA = "RESERVADA";
    public static final String YA_RESERVADA = "YA_RESERVADA";
    public static final String LIBERADA = "LIBERADA";
    public static final String YA_LIBERADA = "YA_LIBERADA";
    public static final String SIN_RESERVA = "SIN_RESERVA";

    private final ProductoRepositorio productos;
    private final ReservaStockRepositorio reservas;
    private final MapeadorProducto mapeador;
    private final ApplicationEventPublisher eventos;

    /**
     * Reserva TODO o NADA.
     *
     * 1. Bloquea (FOR UPDATE, en orden de id) las filas de todos los productos pedidos.
     * 2. Recién con los bloqueos tomados revisa si el pedido ya tenía reserva: así dos
     *    reintentos simultáneos del mismo pedido se serializan y el segundo responde
     *    YA_RESERVADA en vez de descontar de nuevo.
     * 3. Verifica TODOS los ítems antes de tocar NINGUNO; si falta uno se lanza 409 y,
     *    al ser una RuntimeException, la transacción completa hace rollback.
     * 4. Descuenta, guarda la reserva y deja programada la publicación de snapshots para
     *    después del commit.
     */
    @Transactional
    public RespuestaReservaDTO reservar(PeticionReservaDTO peticion) {
        String idPedido = peticion.getIdPedido();
        Map<String, Integer> solicitado = consolidar(peticion.getItems());

        Map<String, Producto> bloqueados = productos.bloquearPorIds(solicitado.keySet()).stream()
                .collect(Collectors.toMap(Producto::getId, Function.identity()));

        Optional<ReservaStock> existente = reservas.findById(idPedido);
        if (existente.isPresent()) {
            ReservaStock r = existente.get();
            if (r.getEstado() == EstadoReserva.LIBERADA) {
                throw new ConflictoReservaException("El pedido " + idPedido
                        + " ya tuvo una reserva y fue liberada; un pedido no puede volver a reservar");
            }
            log.info("Reserva repetida para idPedido={}: se responde la existente sin descontar de nuevo", idPedido);
            return respuesta(idPedido, YA_RESERVADA, r.cantidadesPorProducto());
        }

        List<FaltanteDTO> faltantes = new ArrayList<>();
        solicitado.forEach((productoId, cantidad) -> {
            Producto p = bloqueados.get(productoId);
            if (p == null) {
                // Un producto inexistente se informa como faltante con disponible 0: para quien
                // compra el efecto es el mismo (no se puede llevar) y ms-carrito recibe una sola forma de error.
                faltantes.add(new FaltanteDTO(productoId, 0));
            } else if (p.getStock() < cantidad) {
                faltantes.add(new FaltanteDTO(productoId, p.getStock()));
            }
        });
        if (!faltantes.isEmpty()) {
            throw new StockInsuficienteException(faltantes);
        }

        Instant ahora = Instant.now();
        solicitado.forEach((productoId, cantidad) -> bloqueados.get(productoId).descontarStock(cantidad, ahora));
        // saveAndFlush: si otra transacción insertó el mismo idPedido, la violación de PK aparece
        // aquí (→ 409) y no recién en el commit.
        reservas.saveAndFlush(ReservaStock.nueva(idPedido, solicitado, ahora));

        publicarCambios("reserva " + idPedido, bloqueados.values());
        log.info("Reserva creada idPedido={} items={}", idPedido, solicitado);
        return respuesta(idPedido, RESERVADA, solicitado);
    }

    /**
     * Devuelve el stock de un pedido. Idempotente por idPedido:
     * - reserva RESERVADA → devuelve lo reservado (no lo que diga el cuerpo) y la marca LIBERADA;
     * - reserva ya LIBERADA → 200 YA_LIBERADA sin tocar stock;
     * - pedido sin reserva → 200 SIN_RESERVA sin tocar stock. No se inventa stock a partir del
     *   cuerpo: si no, cualquiera podría "liberar" unidades que nunca se reservaron.
     */
    @Transactional
    public RespuestaReservaDTO liberar(PeticionReservaDTO peticion) {
        String idPedido = peticion.getIdPedido();
        Optional<ReservaStock> opt = reservas.bloquearPorIdPedido(idPedido);
        if (opt.isEmpty()) {
            log.warn("Liberación para idPedido={} sin reserva registrada: no se modifica stock", idPedido);
            return respuesta(idPedido, SIN_RESERVA, Map.of());
        }
        ReservaStock reserva = opt.get();
        Map<String, Integer> reservado = reserva.cantidadesPorProducto();
        if (reserva.getEstado() == EstadoReserva.LIBERADA) {
            log.info("Liberación repetida para idPedido={}: ya estaba liberada, no se devuelve stock otra vez", idPedido);
            return respuesta(idPedido, YA_LIBERADA, reservado);
        }
        Map<String, Integer> pedidoEnCuerpo = consolidar(peticion.getItems());
        if (!pedidoEnCuerpo.equals(reservado)) {
            log.warn("Liberación idPedido={}: el cuerpo {} difiere de lo reservado {}; se devuelve lo reservado",
                    idPedido, pedidoEnCuerpo, reservado);
        }

        Instant ahora = Instant.now();
        List<Producto> bloqueados = productos.bloquearPorIds(reservado.keySet());
        Map<String, Producto> porId = bloqueados.stream().collect(Collectors.toMap(Producto::getId, Function.identity()));
        reservado.forEach((productoId, cantidad) -> {
            Producto p = porId.get(productoId);
            if (p == null) {
                log.warn("Liberación idPedido={}: el producto {} ya no existe; se omite", idPedido, productoId);
            } else {
                p.reponerStock(cantidad, ahora);
            }
        });
        reserva.marcarLiberada(ahora);

        publicarCambios("liberación " + idPedido, bloqueados);
        log.info("Reserva liberada idPedido={} items={}", idPedido, reservado);
        return respuesta(idPedido, LIBERADA, reservado);
    }

    /** Reposición manual (admin): SUMA unidades al stock actual. */
    @Transactional
    public ProductoDTO reponer(String productoId, int cantidad) {
        List<Producto> bloqueado = productos.bloquearPorIds(List.of(productoId));
        if (bloqueado.isEmpty()) {
            throw new ProductoNoEncontradoException(productoId);
        }
        Producto p = bloqueado.get(0);
        int antes = p.getStock();
        p.reponerStock(cantidad, Instant.now());
        publicarCambios("reposición " + productoId, bloqueado);
        log.info("Stock repuesto productoId={} {} → {}", productoId, antes, p.getStock());
        return mapeador.aDTO(p);
    }

    /**
     * Suma cantidades de un mismo producto repetido en la petición y ordena por id: el orden
     * de bloqueo es determinístico y "2 + 3 del mismo producto" se valida como 5, no como dos
     * líneas que por separado alcanzan.
     */
    static Map<String, Integer> consolidar(List<ItemReservaDTO> items) {
        Map<String, Integer> m = new TreeMap<>();
        for (ItemReservaDTO i : items) {
            m.merge(i.getProductoId().trim(), i.getCantidad(), Integer::sum);
        }
        return m;
    }

    private void publicarCambios(String motivo, java.util.Collection<Producto> cambiados) {
        eventos.publishEvent(new CambioCatalogoEvento(motivo, cambiados.stream().map(MapeadorProducto::aSnapshot).toList()));
    }

    private static RespuestaReservaDTO respuesta(String idPedido, String resultado, Map<String, Integer> cantidades) {
        List<ItemReservaDTO> items = cantidades.entrySet().stream()
                .map(e -> new ItemReservaDTO(e.getKey(), e.getValue()))
                .toList();
        return new RespuestaReservaDTO(idPedido, resultado, items, System.currentTimeMillis());
    }
}
