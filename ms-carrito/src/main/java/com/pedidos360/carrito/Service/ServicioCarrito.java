package com.pedidos360.carrito.Service;

import com.pedidos360.carrito.Component.ClienteProductos;
import com.pedidos360.carrito.Component.EjecutorPostCommit;
import com.pedidos360.carrito.Component.ProductorInteracciones;
import com.pedidos360.carrito.Component.PublicadorNotificaciones;
import com.pedidos360.carrito.DTO.AgregarItemDTO;
import com.pedidos360.carrito.DTO.CarritoDTO;
import com.pedidos360.carrito.DTO.EnvioDTO;
import com.pedidos360.carrito.DTO.FaltanteDTO;
import com.pedidos360.carrito.DTO.ProductoDTO;
import com.pedidos360.carrito.DTO.SucursalDTO;
import com.pedidos360.carrito.DTO.TipoNotificacion;
import com.pedidos360.carrito.Exception.CuponNoAplicableException;
import com.pedidos360.carrito.Exception.DatosInvalidosException;
import com.pedidos360.carrito.Exception.RecursoNoEncontradoException;
import com.pedidos360.carrito.Exception.ReglaNegocioException;
import com.pedidos360.carrito.Exception.StockInsuficienteException;
import com.pedidos360.carrito.Model.Carrito;
import com.pedidos360.carrito.Model.Cupon;
import com.pedidos360.carrito.Model.ItemCarrito;
import com.pedidos360.carrito.Model.MetodoEnvio;
import com.pedidos360.carrito.Model.Sucursal;
import com.pedidos360.carrito.Repository.CarritoRepository;
import com.pedidos360.carrito.Repository.CuponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Reglas del carrito: ítems con precio del catálogo, cupones y datos de envío/retiro. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServicioCarrito {

    /** Tope por línea: evita que un error de tipeo (999) reserve el stock entero de un producto. */
    static final int MAXIMO_POR_PRODUCTO = 99;

    private final CarritoRepository carritoRepository;
    private final CuponRepository cuponRepository;
    private final ClienteProductos clienteProductos;
    private final ProductorInteracciones productorInteracciones;
    private final PublicadorNotificaciones publicadorNotificaciones;
    private final EjecutorPostCommit postCommit;
    private final Clock reloj;

    @Transactional(readOnly = true)
    public CarritoDTO obtener(String usuarioId) {
        // Sin carrito guardado se responde uno vacío sin persistirlo: un GET no debe escribir.
        Carrito carrito = carritoRepository.findById(usuarioId).orElseGet(() -> new Carrito(usuarioId));
        return MapeadorDTO.carrito(carrito, calcularTotales(carrito));
    }

    @Transactional
    public CarritoDTO agregarItem(String usuarioId, AgregarItemDTO peticion) {
        // Precio y nombre SIEMPRE desde ms-productos: el cliente sólo dice qué y cuánto.
        ProductoDTO producto = clienteProductos.obtenerProducto(peticion.productoId());
        Carrito carrito = cargarOCrear(usuarioId);

        ItemCarrito item = carrito.buscarItem(producto.id() == null ? peticion.productoId() : producto.id())
                .orElse(null);
        int cantidadFinal = (item == null ? 0 : item.getCantidad()) + peticion.cantidad();
        validarCantidad(producto, cantidadFinal);

        if (item == null) {
            item = ItemCarrito.builder().productoId(peticion.productoId()).build();
            carrito.agregarItem(item);
        }
        copiarDatosProducto(item, producto);
        item.setCantidad(cantidadFinal);
        carrito.setActualizadoEn(ahora());
        carritoRepository.save(carrito);

        ItemCarrito agregado = item;
        Instant instante = ahora();
        postCommit.despuesDeConfirmar(() -> {
            productorInteracciones.publicar(
                    EventosCarrito.agregarCarrito(usuarioId, agregado, peticion.cantidad(), instante));
            publicadorNotificaciones.publicar(TipoNotificacion.CARRITO_PRODUCTO_AGREGADO, usuarioId,
                    "Producto agregado al carrito",
                    "Agregaste " + peticion.cantidad() + " × " + agregado.getNombre() + " a tu carrito",
                    null,
                    Map.of("productoId", agregado.getProductoId(), "cantidad", String.valueOf(peticion.cantidad())));
        });
        log.info("Usuario {} agregó {} × {} al carrito", usuarioId, peticion.cantidad(), peticion.productoId());
        return MapeadorDTO.carrito(carrito, calcularTotales(carrito));
    }

    @Transactional
    public CarritoDTO actualizarCantidad(String usuarioId, String productoId, int cantidad) {
        Carrito carrito = carritoRepository.findById(usuarioId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El producto " + productoId + " no está en el carrito"));
        ItemCarrito item = carrito.buscarItem(productoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El producto " + productoId + " no está en el carrito"));

        if (cantidad == 0) {
            carrito.quitarItem(productoId);
        } else {
            if (cantidad > item.getCantidad()) {
                // Sólo al aumentar se consulta el stock (y de paso se refresca el precio).
                ProductoDTO producto = clienteProductos.obtenerProducto(productoId);
                validarCantidad(producto, cantidad);
                copiarDatosProducto(item, producto);
            }
            item.setCantidad(cantidad);
        }
        carrito.setActualizadoEn(ahora());
        carritoRepository.save(carrito);
        return MapeadorDTO.carrito(carrito, calcularTotales(carrito));
    }

    @Transactional
    public CarritoDTO vaciar(String usuarioId) {
        Carrito carrito = carritoRepository.findById(usuarioId).orElseGet(() -> new Carrito(usuarioId));
        if (carritoRepository.existsById(usuarioId)) {
            carrito.vaciar();
            carrito.setActualizadoEn(ahora());
            carritoRepository.save(carrito);
        }
        return MapeadorDTO.carrito(carrito, calcularTotales(carrito));
    }

    @Transactional
    public CarritoDTO aplicarCupon(String usuarioId, String codigoIngresado) {
        String codigo = codigoIngresado.trim().toUpperCase(Locale.ROOT);
        // Inactivo y desconocido responden igual (404): no se revela qué cupones existieron.
        Cupon cupon = cuponRepository.findById(codigo)
                .filter(Cupon::isActivo)
                .orElseThrow(() -> new RecursoNoEncontradoException("El cupón " + codigo + " no existe o no está vigente"));
        Carrito carrito = cargarOCrear(usuarioId);

        long subtotal = calcularTotales(carrito).subtotal();
        CalculadoraTotales.motivoRechazoCupon(cupon, subtotal).ifPresent(motivo -> {
            throw new CuponNoAplicableException(motivo);
        });

        carrito.setCupon(codigo);
        carrito.setActualizadoEn(ahora());
        carritoRepository.save(carrito);

        List<String> productos = carrito.getItems().stream().map(ItemCarrito::getProductoId).toList();
        Instant instante = ahora();
        postCommit.despuesDeConfirmar(() -> productorInteracciones.publicar(
                EventosCarrito.aplicarCupon(usuarioId, codigo, productos, instante)));
        log.info("Usuario {} aplicó el cupón {}", usuarioId, codigo);
        return MapeadorDTO.carrito(carrito, calcularTotales(carrito));
    }

    @Transactional
    public CarritoDTO quitarCupon(String usuarioId) {
        Carrito carrito = carritoRepository.findById(usuarioId).orElseGet(() -> new Carrito(usuarioId));
        if (carrito.getCupon() != null) {
            carrito.setCupon(null);
            carrito.setActualizadoEn(ahora());
            carritoRepository.save(carrito);
        }
        return MapeadorDTO.carrito(carrito, calcularTotales(carrito));
    }

    @Transactional
    public CarritoDTO configurarEnvio(String usuarioId, EnvioDTO peticion) {
        Carrito carrito = cargarOCrear(usuarioId);
        if (peticion.metodo() == MetodoEnvio.ENVIO) {
            if (vacio(peticion.direccion()) || vacio(peticion.comuna())) {
                throw new DatosInvalidosException("Para ENVIO son obligatorias direccion y comuna");
            }
            carrito.setDireccion(peticion.direccion().trim());
            carrito.setComuna(peticion.comuna().trim());
            carrito.setSucursal(null);
        } else {
            Sucursal sucursal = Sucursal.buscar(peticion.sucursal())
                    .orElseThrow(() -> new DatosInvalidosException("Para RETIRO la sucursal debe ser una de: "
                            + Arrays.stream(Sucursal.values()).map(Sucursal::getEtiqueta)
                            .collect(Collectors.joining(" | "))));
            carrito.setSucursal(sucursal.getEtiqueta());
        }
        carrito.setMetodoEnvio(peticion.metodo());
        carrito.setActualizadoEn(ahora());
        carritoRepository.save(carrito);

        Instant instante = ahora();
        String comuna = carrito.getComuna();
        String sucursal = carrito.getSucursal();
        postCommit.despuesDeConfirmar(() -> productorInteracciones.publicar(
                EventosCarrito.gestionarEnvio(usuarioId, peticion.metodo(), comuna, sucursal, instante)));
        return MapeadorDTO.carrito(carrito, calcularTotales(carrito));
    }

    public List<SucursalDTO> sucursales() {
        return Arrays.stream(Sucursal.values())
                .map(s -> new SucursalDTO(s.name(), s.getNombre(), s.getDireccion(), s.getEtiqueta()))
                .toList();
    }

    /** Misma fórmula para el GET del carrito y para el pedido. */
    CalculadoraTotales.Totales calcularTotales(Carrito carrito) {
        Cupon cupon = null;
        if (carrito.getCupon() != null) {
            // Si el cupón se borró o desactivó después de aplicarlo, deja de descontar (con aviso).
            cupon = cuponRepository.findById(carrito.getCupon())
                    .orElseGet(() -> Cupon.builder().codigo(carrito.getCupon()).activo(false).build());
        }
        var lineas = carrito.getItems().stream()
                .map(i -> new CalculadoraTotales.Linea(i.getPrecioUnitario(), i.getCantidad()))
                .toList();
        return CalculadoraTotales.calcular(lineas, cupon, carrito.getMetodoEnvio());
    }

    static void copiarDatosProducto(ItemCarrito item, ProductoDTO producto) {
        item.setNombre(producto.nombre());
        item.setPrecioUnitario(producto.precio());
        item.setCategoria(producto.categoria());
        item.setSubcategoria(producto.subcategoria());
        item.setMarca(producto.marca());
    }

    private void validarCantidad(ProductoDTO producto, int cantidadFinal) {
        if (cantidadFinal > MAXIMO_POR_PRODUCTO) {
            throw new ReglaNegocioException("Máximo " + MAXIMO_POR_PRODUCTO + " unidades por producto");
        }
        // Chequeo temprano y orientativo: la garantía real es la reserva al crear el pedido.
        if (producto.stock() != null && cantidadFinal > producto.stock()) {
            throw new StockInsuficienteException("Stock insuficiente para " + producto.nombre(),
                    List.of(new FaltanteDTO(producto.id(), producto.stock())));
        }
    }

    private Carrito cargarOCrear(String usuarioId) {
        return carritoRepository.findById(usuarioId).orElseGet(() -> new Carrito(usuarioId));
    }

    private Instant ahora() {
        return reloj.instant();
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
