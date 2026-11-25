package com.pedidos360.carrito.Service;

import com.pedidos360.carrito.Component.ClienteProductos;
import com.pedidos360.carrito.Component.EjecutorPostCommit;
import com.pedidos360.carrito.Component.ProductorInteracciones;
import com.pedidos360.carrito.Component.PublicadorNotificaciones;
import com.pedidos360.carrito.DTO.EstadoPago;
import com.pedidos360.carrito.DTO.LineaStockDTO;
import com.pedidos360.carrito.DTO.MovimientoStockDTO;
import com.pedidos360.carrito.DTO.PedidoDTO;
import com.pedidos360.carrito.DTO.ProductoDTO;
import com.pedidos360.carrito.DTO.ResultadoPagoDTO;
import com.pedidos360.carrito.DTO.TipoNotificacion;
import com.pedidos360.carrito.Exception.RecursoNoEncontradoException;
import com.pedidos360.carrito.Exception.ReglaNegocioException;
import com.pedidos360.carrito.Model.Carrito;
import com.pedidos360.carrito.Model.EstadoPedido;
import com.pedidos360.carrito.Model.ItemCarrito;
import com.pedidos360.carrito.Model.ItemPedido;
import com.pedidos360.carrito.Model.MetodoEnvio;
import com.pedidos360.carrito.Model.Pedido;
import com.pedidos360.carrito.Model.SecuenciaPedido;
import com.pedidos360.carrito.Repository.CarritoRepository;
import com.pedidos360.carrito.Repository.PedidoRepository;
import com.pedidos360.carrito.Repository.SecuenciaPedidoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ciclo de vida del pedido: creación con reserva de stock (REST), resultado del pago
 * (coreografía vía pagos-resultado) y avance de la logística simulada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServicioPedidos {

    private final CarritoRepository carritoRepository;
    private final PedidoRepository pedidoRepository;
    private final SecuenciaPedidoRepository secuenciaPedidoRepository;
    private final ServicioCarrito servicioCarrito;
    private final ClienteProductos clienteProductos;
    private final ProductorInteracciones productorInteracciones;
    private final PublicadorNotificaciones publicadorNotificaciones;
    private final EjecutorPostCommit postCommit;
    private final Clock reloj;

    // =====================================================================
    // Creación
    // =====================================================================

    @Transactional
    public PedidoDTO crearPedido(String usuarioId) {
        Carrito carrito = carritoRepository.findById(usuarioId)
                .filter(c -> !c.getItems().isEmpty())
                .orElseThrow(() -> new ReglaNegocioException("El carrito está vacío"));
        String destino = resolverDestino(carrito);

        // Se confirma el precio vigente justo antes de comprar: un precio guardado hace horas en
        // el carrito no debe ser el que se cobra si el catálogo cambió.
        for (ItemCarrito item : carrito.getItems()) {
            ProductoDTO producto = clienteProductos.obtenerProducto(item.getProductoId());
            ServicioCarrito.copiarDatosProducto(item, producto);
        }
        CalculadoraTotales.Totales totales = servicioCarrito.calcularTotales(carrito);

        Instant ahora = reloj.instant();
        String idPedido = "P-" + secuenciaPedidoRepository.save(new SecuenciaPedido(ahora)).getId();
        List<LineaStockDTO> lineas = carrito.getItems().stream()
                .map(i -> new LineaStockDTO(i.getProductoId(), i.getCantidad()))
                .toList();
        MovimientoStockDTO movimiento = new MovimientoStockDTO(idPedido, lineas);

        // 409 (faltantes) o 503 se propagan tal cual y la transacción se revierte: no queda pedido.
        clienteProductos.reservarStock(movimiento);
        // Si algo falla DESPUÉS de reservar (BD, commit), se devuelve el stock: sin esto quedaría
        // retenido para un pedido que nunca existió.
        postCommit.siSeRevierte(() -> {
            log.warn("Se revirtió la creación del pedido {}: liberando el stock reservado", idPedido);
            clienteProductos.liberarStock(movimiento);
        });

        Pedido pedido = new Pedido();
        pedido.setId(idPedido);
        pedido.setUsuarioId(usuarioId);
        pedido.setMetodoEnvio(carrito.getMetodoEnvio());
        pedido.setDestino(destino);
        pedido.setSubtotal(totales.subtotal());
        pedido.setDescuento(totales.descuento());
        pedido.setCostoEnvio(totales.costoEnvio());
        pedido.setTotal(totales.total());
        // Se registra el cupón sólo si efectivamente aplicó (lo que se ve es lo que se paga).
        pedido.setCupon(totales.cuponAplicado() ? carrito.getCupon() : null);
        for (ItemCarrito i : carrito.getItems()) {
            pedido.agregarItem(ItemPedido.builder()
                    .productoId(i.getProductoId()).nombre(i.getNombre()).precioUnitario(i.getPrecioUnitario())
                    .cantidad(i.getCantidad()).categoria(i.getCategoria()).subcategoria(i.getSubcategoria())
                    .marca(i.getMarca()).build());
        }
        pedido.iniciar(ahora);
        pedidoRepository.save(pedido);

        carrito.vaciar();
        carrito.setActualizadoEn(ahora);
        carritoRepository.save(carrito);

        postCommit.despuesDeConfirmar(() -> publicadorNotificaciones.publicar(TipoNotificacion.PEDIDO_CREADO,
                usuarioId, "Pedido " + idPedido + " creado",
                "Tu pedido por " + Formato.clp(pedido.getTotal()) + " está pendiente de pago", idPedido,
                Map.of("total", String.valueOf(pedido.getTotal()), "metodoEnvio", pedido.getMetodoEnvio().name())));
        log.info("Pedido {} creado para {}: total={} método={} destino={}",
                idPedido, usuarioId, pedido.getTotal(), pedido.getMetodoEnvio(), destino);
        return MapeadorDTO.pedido(pedido);
    }

    private String resolverDestino(Carrito carrito) {
        if (carrito.getMetodoEnvio() == null) {
            throw new ReglaNegocioException("Falta elegir el método de envío (ENVIO o RETIRO)");
        }
        if (carrito.getMetodoEnvio() == MetodoEnvio.ENVIO) {
            if (vacio(carrito.getDireccion()) || vacio(carrito.getComuna())) {
                throw new ReglaNegocioException("Faltan la dirección y/o la comuna de despacho");
            }
            return carrito.getDireccion() + ", " + carrito.getComuna();
        }
        if (vacio(carrito.getSucursal())) {
            throw new ReglaNegocioException("Falta elegir la sucursal de retiro");
        }
        return carrito.getSucursal();
    }

    // =====================================================================
    // Resultado del pago (consumidor grupo-carrito)
    // =====================================================================

    /**
     * Idempotente: Kafka entrega al menos una vez, así que un resultado repetido (o uno que llega
     * después de que el pedido ya se resolvió) se ignora con log en vez de fallar.
     */
    @Transactional
    public ResultadoProcesamiento procesarResultadoPago(ResultadoPagoDTO resultado) {
        Optional<Pedido> encontrado = pedidoRepository.findById(resultado.getIdOrden());
        if (encontrado.isEmpty()) {
            // pagos-resultado también trae los pagos hechos desde la consola: no son pedidos de la tienda.
            log.warn("Resultado de pago para idOrden={} que no corresponde a un pedido de la tienda: se ignora",
                    resultado.getIdOrden());
            return ResultadoProcesamiento.IGNORADO_DESCONOCIDO;
        }
        Pedido pedido = encontrado.get();
        if (pedido.getEstado() != EstadoPedido.PENDIENTE_PAGO) {
            log.info("Resultado {} repetido para el pedido {} que ya está en {}: se ignora (idempotencia)",
                    resultado.getEstado(), pedido.getId(), pedido.getEstado());
            return ResultadoProcesamiento.IGNORADO_DUPLICADO;
        }
        if (resultado.getIdCliente() != null && !resultado.getIdCliente().equals(pedido.getUsuarioId())) {
            log.warn("El pago del pedido {} viene con idCliente={} pero el pedido es de {}",
                    pedido.getId(), resultado.getIdCliente(), pedido.getUsuarioId());
        }

        if (resultado.getEstado() == EstadoPago.AUTORIZADO) {
            // La tienda le pasa el monto a ms-pago: si no coincide con el total calculado aquí,
            // alguien pagó otro monto. No se despacha; queda como rechazado para revisión manual.
            if (resultado.getMonto() != null && !Objects.equals(resultado.getMonto(), pedido.getTotal())) {
                log.error("Pago AUTORIZADO del pedido {} por {} no coincide con el total {}: se marca PAGO_RECHAZADO "
                                + "y requiere reverso manual", pedido.getId(), resultado.getMonto(), pedido.getTotal());
                return rechazar(pedido, "El monto pagado no coincide con el total del pedido");
            }
            return marcarPagado(pedido, resultado);
        }
        return rechazar(pedido, "El pago fue " + resultado.getEstado().name().toLowerCase());
    }

    private ResultadoProcesamiento marcarPagado(Pedido pedido, ResultadoPagoDTO resultado) {
        Instant ahora = reloj.instant();
        pedido.cambiarEstado(EstadoPedido.PAGADO, ahora);
        pedido.setCodigoAutorizacion(resultado.getCodigoAutorizacion());
        pedidoRepository.save(pedido);

        String usuarioId = pedido.getUsuarioId();
        String idPedido = pedido.getId();
        List<ItemPedido> items = List.copyOf(pedido.getItems());
        long total = pedido.getTotal();
        postCommit.despuesDeConfirmar(() -> {
            // Un COMPRA por ítem (§8): ms-recomendaciones pondera cada producto comprado.
            items.forEach(i -> productorInteracciones.publicar(EventosCarrito.compra(usuarioId, idPedido, i, ahora)));
            publicadorNotificaciones.publicar(TipoNotificacion.PEDIDO_PAGADO, usuarioId,
                    "Pago confirmado", "Recibimos el pago de tu pedido " + idPedido + " por " + Formato.clp(total),
                    idPedido, Map.of("estado", EstadoPedido.PAGADO.name(), "total", String.valueOf(total)));
        });
        log.info("Pedido {} PAGADO (código {})", idPedido, resultado.getCodigoAutorizacion());
        return ResultadoProcesamiento.PAGADO;
    }

    private ResultadoProcesamiento rechazar(Pedido pedido, String motivo) {
        pedido.cambiarEstado(EstadoPedido.PAGO_RECHAZADO, reloj.instant());
        pedidoRepository.save(pedido);
        // Dentro de la transacción a propósito: si ms-productos no responde, se lanza, la BD se
        // revierte y Kafka reintenta todo el mensaje (la liberación es idempotente por idPedido).
        clienteProductos.liberarStock(new MovimientoStockDTO(pedido.getId(), pedido.getItems().stream()
                .map(i -> new LineaStockDTO(i.getProductoId(), i.getCantidad())).toList()));

        String usuarioId = pedido.getUsuarioId();
        String idPedido = pedido.getId();
        postCommit.despuesDeConfirmar(() -> publicadorNotificaciones.publicar(TipoNotificacion.PEDIDO_RECHAZADO,
                usuarioId, "Pago rechazado", motivo + ". Tu pedido " + idPedido + " fue cancelado.", idPedido,
                Map.of("estado", EstadoPedido.PAGO_RECHAZADO.name(), "motivo", motivo)));
        log.info("Pedido {} PAGO_RECHAZADO: {}", idPedido, motivo);
        return ResultadoProcesamiento.RECHAZADO;
    }

    // =====================================================================
    // Logística simulada (scheduler)
    // =====================================================================

    @Transactional(readOnly = true)
    public List<String> idsEnCurso() {
        return pedidoRepository.idsPorEstado(EstadoPedido.enCurso());
    }

    /** Avanza un paso. Una transacción por pedido: el fallo de uno no revierte a los demás. */
    @Transactional
    public Optional<EstadoPedido> avanzarPedido(String idPedido) {
        Optional<Pedido> encontrado = pedidoRepository.findById(idPedido);
        if (encontrado.isEmpty()) {
            return Optional.empty();
        }
        Pedido pedido = encontrado.get();
        Optional<EstadoPedido> siguiente = MaquinaEstadosPedido.siguienteLogistico(pedido.getEstado(), pedido.getMetodoEnvio());
        if (siguiente.isEmpty()) {
            return Optional.empty();
        }
        EstadoPedido anterior = pedido.getEstado();
        pedido.cambiarEstado(siguiente.get(), reloj.instant());
        pedidoRepository.save(pedido);

        String usuarioId = pedido.getUsuarioId();
        EstadoPedido nuevo = siguiente.get();
        postCommit.despuesDeConfirmar(() -> publicadorNotificaciones.publicar(TipoNotificacion.ENVIO_ESTADO_ACTUALIZADO,
                usuarioId, "Tu pedido " + idPedido + " está " + textoEstado(nuevo),
                "Estado actualizado: " + textoEstado(anterior) + " → " + textoEstado(nuevo), idPedido,
                Map.of("estadoAnterior", anterior.name(), "estado", nuevo.name())));
        log.info("Logística simulada: pedido {} {} → {}", idPedido, anterior, nuevo);
        return siguiente;
    }

    // =====================================================================
    // Consultas
    // =====================================================================

    @Transactional(readOnly = true)
    public List<PedidoDTO> listarDelUsuario(String usuarioId) {
        return pedidoRepository.findByUsuarioIdOrderByCreadoEnDesc(usuarioId).stream().map(MapeadorDTO::pedido).toList();
    }

    @Transactional(readOnly = true)
    public PedidoDTO obtenerDelUsuario(String usuarioId, String idPedido) {
        // Un pedido ajeno responde 404, no 403: no se confirma que el id exista.
        return pedidoRepository.findById(idPedido)
                .filter(p -> p.getUsuarioId().equals(usuarioId))
                .map(MapeadorDTO::pedido)
                .orElseThrow(() -> new RecursoNoEncontradoException("El pedido " + idPedido + " no existe"));
    }

    @Transactional(readOnly = true)
    public List<PedidoDTO> listarTodos(EstadoPedido estado) {
        List<Pedido> pedidos = estado == null
                ? pedidoRepository.findAllByOrderByCreadoEnDesc()
                : pedidoRepository.findByEstadoOrderByCreadoEnDesc(estado);
        return pedidos.stream().map(MapeadorDTO::pedido).toList();
    }

    static String textoEstado(EstadoPedido estado) {
        return switch (estado) {
            case PENDIENTE_PAGO -> "pendiente de pago";
            case PAGO_RECHAZADO -> "con pago rechazado";
            case PAGADO -> "pagado";
            case PREPARANDO -> "en preparación";
            case ENVIADO -> "enviado";
            case EN_CAMINO -> "en camino";
            case ENTREGADO -> "entregado";
            case LISTO_RETIRO -> "listo para retiro";
            case RETIRADO -> "retirado";
        };
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
