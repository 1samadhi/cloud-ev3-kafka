package com.pedidos360.carrito.Service;

import com.pedidos360.carrito.Component.ClienteProductos;
import com.pedidos360.carrito.Component.EjecutorPostCommit;
import com.pedidos360.carrito.Component.ProductorInteracciones;
import com.pedidos360.carrito.Component.PublicadorNotificaciones;
import com.pedidos360.carrito.Config.SemillaCupones;
import com.pedidos360.carrito.DTO.EstadoPago;
import com.pedidos360.carrito.DTO.EventoInteraccionDTO;
import com.pedidos360.carrito.DTO.FaltanteDTO;
import com.pedidos360.carrito.DTO.MovimientoStockDTO;
import com.pedidos360.carrito.DTO.Pasarela;
import com.pedidos360.carrito.DTO.PedidoDTO;
import com.pedidos360.carrito.DTO.ProductoDTO;
import com.pedidos360.carrito.DTO.ResultadoPagoDTO;
import com.pedidos360.carrito.DTO.TipoEvento;
import com.pedidos360.carrito.DTO.TipoNotificacion;
import com.pedidos360.carrito.Exception.ServicioNoDisponibleException;
import com.pedidos360.carrito.Exception.StockInsuficienteException;
import com.pedidos360.carrito.Model.Carrito;
import com.pedidos360.carrito.Model.EstadoPedido;
import com.pedidos360.carrito.Model.ItemCarrito;
import com.pedidos360.carrito.Model.MetodoEnvio;
import com.pedidos360.carrito.Repository.CarritoRepository;
import com.pedidos360.carrito.Repository.CuponRepository;
import com.pedidos360.carrito.Repository.PedidoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ServicioPedidos con JPA real (H2) y transacciones reales: la prueba NO es transaccional a
 * propósito, así los callbacks post-commit (eventos Kafka y notificaciones RabbitMQ) se ejecutan
 * igual que en producción. ms-productos, Kafka y RabbitMQ van mockeados.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ServicioPedidos.class, ServicioCarrito.class, EjecutorPostCommit.class, ServicioPedidosTest.Reloj.class})
class ServicioPedidosTest {

    static final Instant AHORA = Instant.parse("2026-11-25T15:00:00Z");

    @TestConfiguration
    static class Reloj {
        @Bean
        Clock reloj() {
            return Clock.fixed(AHORA, ZoneOffset.UTC);
        }
    }

    @Autowired ServicioPedidos servicioPedidos;
    @Autowired ServicioCarrito servicioCarrito;
    @Autowired CarritoRepository carritoRepository;
    @Autowired PedidoRepository pedidoRepository;
    @Autowired CuponRepository cuponRepository;

    @MockitoBean ClienteProductos clienteProductos;
    @MockitoBean ProductorInteracciones productorInteracciones;
    @MockitoBean PublicadorNotificaciones publicadorNotificaciones;

    @BeforeEach
    void preparar() {
        cuponRepository.saveAll(SemillaCupones.cuponesSemilla());
        when(clienteProductos.obtenerProducto("item_101"))
                .thenReturn(new ProductoDTO("item_101", "Galaxy S24", "Samsung", "celulares", "gama-alta", 649_990L, 14));
        when(clienteProductos.obtenerProducto("item_112"))
                .thenReturn(new ProductoDTO("item_112", "DualSense", "Sony", "gaming", "controles", 74_990L, 70));
        when(clienteProductos.obtenerProducto("item_117"))
                .thenReturn(new ProductoDTO("item_117", "Cargador GaN 65 W", "Anker", "accesorios", "carga", 34_990L, 80));
    }

    @AfterEach
    void limpiar() {
        pedidoRepository.deleteAll();
        carritoRepository.deleteAll();
        cuponRepository.deleteAll();
    }

    /** Carrito persistido con precios posiblemente viejos: crearPedido debe refrescarlos desde ms-productos. */
    private void carrito(String usuario, MetodoEnvio metodo, String cupon, Object... productoCantidad) {
        Carrito c = new Carrito(usuario);
        for (int i = 0; i < productoCantidad.length; i += 2) {
            c.agregarItem(ItemCarrito.builder().productoId((String) productoCantidad[i]).nombre("viejo")
                    .precioUnitario(1).cantidad((Integer) productoCantidad[i + 1]).build());
        }
        c.setMetodoEnvio(metodo);
        if (metodo == MetodoEnvio.ENVIO) {
            c.setDireccion("Av. Siempre Viva 742");
            c.setComuna("Ñuñoa");
        } else {
            c.setSucursal("Maipú · Av. Pajaritos 3450");
        }
        c.setCupon(cupon);
        carritoRepository.save(c);
    }

    private ResultadoPagoDTO resultado(PedidoDTO p, EstadoPago estado, Long monto) {
        return ResultadoPagoDTO.builder().idOrden(p.id()).idCliente(p.usuarioId()).estado(estado).monto(monto)
                .codigoAutorizacion(estado == EstadoPago.AUTORIZADO ? "1213" : null).ultimos4("6623")
                .pasarela(Pasarela.SIMULADA).timestamp(AHORA.toEpochMilli()).build();
    }

    @Test
    void crearPedidoReservaStockConPreciosVigentesVaciaElCarritoYNotifica() {
        carrito("u1", MetodoEnvio.ENVIO, null, "item_101", 1, "item_112", 2);

        PedidoDTO p = servicioPedidos.crearPedido("u1");

        assertThat(p.id()).startsWith("P-");
        assertThat(p.estado()).isEqualTo(EstadoPedido.PENDIENTE_PAGO);
        assertThat(p.subtotal()).isEqualTo(649_990 + 2 * 74_990);
        assertThat(p.costoEnvio()).isZero(); // ≥ 100 000 → envío gratis
        assertThat(p.total()).isEqualTo(799_970);
        assertThat(p.destino()).isEqualTo("Av. Siempre Viva 742, Ñuñoa");
        assertThat(p.historial()).extracting(h -> h.estado()).containsExactly(EstadoPedido.PENDIENTE_PAGO);

        ArgumentCaptor<MovimientoStockDTO> reserva = ArgumentCaptor.forClass(MovimientoStockDTO.class);
        verify(clienteProductos).reservarStock(reserva.capture());
        assertThat(reserva.getValue().idPedido()).isEqualTo(p.id());
        assertThat(reserva.getValue().items()).extracting(l -> l.productoId() + "x" + l.cantidad())
                .containsExactly("item_101x1", "item_112x2");

        assertThat(servicioCarrito.obtener("u1").items()).isEmpty();
        verify(publicadorNotificaciones).publicar(eq(TipoNotificacion.PEDIDO_CREADO), eq("u1"), anyString(), anyString(),
                eq(p.id()), anyMap());
    }

    @Test
    void sinStockPropaga409YNoCreaPedidoNiVaciaElCarrito() {
        carrito("u2", MetodoEnvio.RETIRO, null, "item_112", 3);
        doThrow(new StockInsuficienteException("sin stock", List.of(new FaltanteDTO("item_112", 1))))
                .when(clienteProductos).reservarStock(any());

        assertThatThrownBy(() -> servicioPedidos.crearPedido("u2"))
                .isInstanceOf(StockInsuficienteException.class)
                .satisfies(e -> assertThat(((StockInsuficienteException) e).getFaltantes())
                        .containsExactly(new FaltanteDTO("item_112", 1)));

        assertThat(pedidoRepository.count()).isZero();
        assertThat(servicioCarrito.obtener("u2").items()).hasSize(1);
        verify(publicadorNotificaciones, never()).publicar(any(), any(), any(), any(), any(), any());
    }

    @Test
    void autorizadoPasaAPagadoPublicaCompraPorItemYNotifica() {
        carrito("u3", MetodoEnvio.ENVIO, null, "item_101", 1, "item_112", 2);
        PedidoDTO p = servicioPedidos.crearPedido("u3");

        ResultadoProcesamiento r = servicioPedidos.procesarResultadoPago(resultado(p, EstadoPago.AUTORIZADO, p.total()));

        assertThat(r).isEqualTo(ResultadoProcesamiento.PAGADO);
        PedidoDTO pagado = servicioPedidos.obtenerDelUsuario("u3", p.id());
        assertThat(pagado.estado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(pagado.codigoAutorizacion()).isEqualTo("1213");
        assertThat(pagado.pagadoEn()).isEqualTo(AHORA);
        assertThat(pagado.historial()).extracting(h -> h.estado())
                .containsExactly(EstadoPedido.PENDIENTE_PAGO, EstadoPedido.PAGADO);

        ArgumentCaptor<EventoInteraccionDTO> eventos = ArgumentCaptor.forClass(EventoInteraccionDTO.class);
        verify(productorInteracciones, times(2)).publicar(eventos.capture());
        assertThat(eventos.getAllValues()).allSatisfy(e -> {
            assertThat(e.getTipoEvento()).isEqualTo(TipoEvento.COMPRA);
            assertThat(e.getUsuarioId()).isEqualTo("u3");
            assertThat(e.getOrigen()).isEqualTo("ms-carrito");
            assertThat(e.getDetalle()).containsEntry("idPedido", p.id());
        });
        assertThat(eventos.getAllValues()).extracting(EventoInteraccionDTO::getProductoId)
                .containsExactlyInAnyOrder("item_101", "item_112");
        assertThat(eventos.getAllValues()).extracting(EventoInteraccionDTO::getCategoria)
                .containsExactlyInAnyOrder("celulares", "gaming");
        verify(publicadorNotificaciones).publicar(eq(TipoNotificacion.PEDIDO_PAGADO), eq("u3"), anyString(), anyString(),
                eq(p.id()), anyMap());
        verify(clienteProductos, never()).liberarStock(any());
    }

    @Test
    void rechazadoLiberaStockYNotifica() {
        carrito("u4", MetodoEnvio.RETIRO, null, "item_117", 2);
        PedidoDTO p = servicioPedidos.crearPedido("u4");

        ResultadoProcesamiento r = servicioPedidos.procesarResultadoPago(resultado(p, EstadoPago.RECHAZADO, p.total()));

        assertThat(r).isEqualTo(ResultadoProcesamiento.RECHAZADO);
        assertThat(servicioPedidos.obtenerDelUsuario("u4", p.id()).estado()).isEqualTo(EstadoPedido.PAGO_RECHAZADO);
        ArgumentCaptor<MovimientoStockDTO> liberacion = ArgumentCaptor.forClass(MovimientoStockDTO.class);
        verify(clienteProductos).liberarStock(liberacion.capture());
        assertThat(liberacion.getValue().idPedido()).isEqualTo(p.id());
        assertThat(liberacion.getValue().items()).extracting(l -> l.productoId() + "x" + l.cantidad())
                .containsExactly("item_117x2");
        verify(publicadorNotificaciones).publicar(eq(TipoNotificacion.PEDIDO_RECHAZADO), eq("u4"), anyString(), anyString(),
                eq(p.id()), anyMap());
        verify(productorInteracciones, never()).publicar(any());
    }

    @Test
    void anuladoYErrorTambienRechazan() {
        carrito("u5a", MetodoEnvio.RETIRO, null, "item_117", 1);
        PedidoDTO a = servicioPedidos.crearPedido("u5a");
        carrito("u5b", MetodoEnvio.RETIRO, null, "item_117", 1);
        PedidoDTO b = servicioPedidos.crearPedido("u5b");

        assertThat(servicioPedidos.procesarResultadoPago(resultado(a, EstadoPago.ANULADO, null)))
                .isEqualTo(ResultadoProcesamiento.RECHAZADO);
        assertThat(servicioPedidos.procesarResultadoPago(resultado(b, EstadoPago.ERROR, null)))
                .isEqualTo(ResultadoProcesamiento.RECHAZADO);
        verify(clienteProductos, times(2)).liberarStock(any());
    }

    @Test
    void resultadoRepetidoSeIgnoraIdempotencia() {
        carrito("u6", MetodoEnvio.ENVIO, null, "item_101", 1);
        PedidoDTO p = servicioPedidos.crearPedido("u6");
        ResultadoPagoDTO autorizado = resultado(p, EstadoPago.AUTORIZADO, p.total());

        assertThat(servicioPedidos.procesarResultadoPago(autorizado)).isEqualTo(ResultadoProcesamiento.PAGADO);
        assertThat(servicioPedidos.procesarResultadoPago(autorizado)).isEqualTo(ResultadoProcesamiento.IGNORADO_DUPLICADO);
        // Un RECHAZADO tardío tampoco deshace un pedido ya pagado ni libera su stock.
        assertThat(servicioPedidos.procesarResultadoPago(resultado(p, EstadoPago.RECHAZADO, p.total())))
                .isEqualTo(ResultadoProcesamiento.IGNORADO_DUPLICADO);

        assertThat(servicioPedidos.obtenerDelUsuario("u6", p.id()).estado()).isEqualTo(EstadoPedido.PAGADO);
        verify(productorInteracciones, times(1)).publicar(any());
        verify(publicadorNotificaciones, times(1)).publicar(eq(TipoNotificacion.PEDIDO_PAGADO), any(), any(), any(), any(), any());
        verify(clienteProductos, never()).liberarStock(any());
    }

    @Test
    void idOrdenDesconocidoSeIgnora() {
        ResultadoPagoDTO ajeno = ResultadoPagoDTO.builder().idOrden("ORD-CONSOLA-1").estado(EstadoPago.AUTORIZADO).build();
        assertThat(servicioPedidos.procesarResultadoPago(ajeno)).isEqualTo(ResultadoProcesamiento.IGNORADO_DESCONOCIDO);
    }

    @Test
    void autorizadoConMontoDistintoNoSeDespacha() {
        carrito("u7", MetodoEnvio.RETIRO, null, "item_117", 1);
        PedidoDTO p = servicioPedidos.crearPedido("u7");

        assertThat(servicioPedidos.procesarResultadoPago(resultado(p, EstadoPago.AUTORIZADO, 1L)))
                .isEqualTo(ResultadoProcesamiento.RECHAZADO);
        assertThat(servicioPedidos.obtenerDelUsuario("u7", p.id()).estado()).isEqualTo(EstadoPedido.PAGO_RECHAZADO);
        verify(clienteProductos).liberarStock(any());
        verify(productorInteracciones, never()).publicar(any());
    }

    @Test
    void siMsProductosNoRespondeAlLiberarSeRevierteParaQueKafkaReintente() {
        carrito("u8", MetodoEnvio.RETIRO, null, "item_117", 1);
        PedidoDTO p = servicioPedidos.crearPedido("u8");
        doThrow(new ServicioNoDisponibleException("caído")).when(clienteProductos).liberarStock(any());

        assertThatThrownBy(() -> servicioPedidos.procesarResultadoPago(resultado(p, EstadoPago.RECHAZADO, null)))
                .isInstanceOf(ServicioNoDisponibleException.class);

        assertThat(servicioPedidos.obtenerDelUsuario("u8", p.id()).estado()).isEqualTo(EstadoPedido.PENDIENTE_PAGO);
        verify(publicadorNotificaciones, never()).publicar(eq(TipoNotificacion.PEDIDO_RECHAZADO), any(), any(), any(), any(), any());
    }

    @Test
    void cuponQueAplicaQuedaEnElPedido() {
        carrito("u9", MetodoEnvio.ENVIO, "BIENVENIDA10", "item_117", 1);
        PedidoDTO p = servicioPedidos.crearPedido("u9");
        assertThat(p.cupon()).isEqualTo("BIENVENIDA10");
        assertThat(p.descuento()).isEqualTo(3_499); // 10 % de 34 990 (división entera)
        assertThat(p.costoEnvio()).isEqualTo(3_990);
        assertThat(p.total()).isEqualTo(34_990 - 3_499 + 3_990);
    }

    @Test
    void logisticaAvanzaUnPasoYNotificaEnvio() {
        carrito("u10", MetodoEnvio.RETIRO, null, "item_117", 1);
        PedidoDTO p = servicioPedidos.crearPedido("u10");
        servicioPedidos.procesarResultadoPago(resultado(p, EstadoPago.AUTORIZADO, p.total()));

        assertThat(servicioPedidos.idsEnCurso()).containsExactly(p.id());
        assertThat(servicioPedidos.avanzarPedido(p.id())).contains(EstadoPedido.PREPARANDO);
        assertThat(servicioPedidos.avanzarPedido(p.id())).contains(EstadoPedido.LISTO_RETIRO);
        assertThat(servicioPedidos.avanzarPedido(p.id())).contains(EstadoPedido.RETIRADO);
        assertThat(servicioPedidos.avanzarPedido(p.id())).isEmpty();
        assertThat(servicioPedidos.idsEnCurso()).isEmpty();
        verify(publicadorNotificaciones, times(3)).publicar(eq(TipoNotificacion.ENVIO_ESTADO_ACTUALIZADO), eq("u10"),
                anyString(), anyString(), eq(p.id()), anyMap());
    }

    @Test
    void pedidoAjenoResponde404() {
        carrito("u11", MetodoEnvio.RETIRO, null, "item_117", 1);
        PedidoDTO p = servicioPedidos.crearPedido("u11");
        assertThatThrownBy(() -> servicioPedidos.obtenerDelUsuario("otro", p.id()))
                .isInstanceOf(com.pedidos360.carrito.Exception.RecursoNoEncontradoException.class);
    }
}
