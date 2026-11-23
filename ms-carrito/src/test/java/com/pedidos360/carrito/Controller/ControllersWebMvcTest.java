package com.pedidos360.carrito.Controller;

import com.pedidos360.carrito.Component.UsuarioActual;
import com.pedidos360.carrito.DTO.CarritoDTO;
import com.pedidos360.carrito.DTO.FaltanteDTO;
import com.pedidos360.carrito.DTO.PedidoDTO;
import com.pedidos360.carrito.DTO.ResumenAdminDTO;
import com.pedidos360.carrito.Exception.CuponNoAplicableException;
import com.pedidos360.carrito.Exception.RecursoNoEncontradoException;
import com.pedidos360.carrito.Exception.ReglaNegocioException;
import com.pedidos360.carrito.Exception.ServicioNoDisponibleException;
import com.pedidos360.carrito.Exception.StockInsuficienteException;
import com.pedidos360.carrito.Model.EstadoPedido;
import com.pedidos360.carrito.Model.MetodoEnvio;
import com.pedidos360.carrito.Service.ServicioCarrito;
import com.pedidos360.carrito.Service.ServicioPedidos;
import com.pedidos360.carrito.Service.ServicioResumenAdmin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Validación y códigos HTTP de los 3 controllers (servicios mockeados). */
@WebMvcTest(controllers = {CarritoController.class, PedidosController.class, AdminController.class})
@Import(UsuarioActual.class)
class ControllersWebMvcTest {

    static final String U = "X-Usuario-Id";

    @Autowired MockMvc mvc;
    @MockitoBean ServicioCarrito servicioCarrito;
    @MockitoBean ServicioPedidos servicioPedidos;
    @MockitoBean ServicioResumenAdmin servicioResumenAdmin;

    private static CarritoDTO carritoVacio(String u) {
        return new CarritoDTO(u, List.of(), 0, null, false, null, null, null, null, null, false,
                0, 0, 0, 0, 100_000, 100_000, null);
    }

    private static PedidoDTO pedido(String id) {
        return new PedidoDTO(id, "u1", EstadoPedido.PENDIENTE_PAGO, MetodoEnvio.RETIRO, "Maipú · Av. Pajaritos 3450",
                List.of(), 34_990, 0, 0, 34_990, null, null, Instant.EPOCH, Instant.EPOCH, null, List.of());
    }

    // ---------------------------------------------------------------- 400

    @Test
    void sinHeaderDeUsuario400() throws Exception {
        mvc.perform(get("/api/carrito"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Datos inválidos"))
                .andExpect(jsonPath("$.detalle").value(containsString("X-Usuario-Id")))
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void headerDeUsuarioConCaracteresInvalidos400() throws Exception {
        mvc.perform(get("/api/carrito").header(U, "<script>")).andExpect(status().isBadRequest());
    }

    @Test
    void agregarConCantidadCeroYSinProducto400() throws Exception {
        mvc.perform(post("/api/carrito/items").header(U, "u1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productoId\":\"\",\"cantidad\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle").value(containsString("cantidad")))
                .andExpect(jsonPath("$.detalle").value(containsString("productoId")));
        verify(servicioCarrito, never()).agregarItem(any(), any());
    }

    @Test
    void cantidadNegativaEnPatch400() throws Exception {
        mvc.perform(patch("/api/carrito/items/item_101").header(U, "u1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidad\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void metodoDeEnvioFueraDelEnum400() throws Exception {
        mvc.perform(put("/api/carrito/envio").header(U, "u1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metodo\":\"DRON\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cuerpo de la petición ilegible"));
    }

    @Test
    void jsonMalFormado400() throws Exception {
        mvc.perform(post("/api/carrito/cupon").header(U, "u1").contentType(MediaType.APPLICATION_JSON).content("{codigo"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void estadoAdminInvalido400() throws Exception {
        mvc.perform(get("/api/admin/pedidos").param("estado", "VOLANDO")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- 200 / 201

    @Test
    void agregarValidoDelegaConElUsuarioDelHeader() throws Exception {
        when(servicioCarrito.agregarItem(eq("u1"), any())).thenReturn(carritoVacio("u1"));
        mvc.perform(post("/api/carrito/items").header(U, "u1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productoId\":\"item_101\",\"cantidad\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").value("u1"))
                .andExpect(jsonPath("$.envioGratisDesde").value(100000));
    }

    @Test
    void crearPedido201ConLocation() throws Exception {
        when(servicioPedidos.crearPedido("u1")).thenReturn(pedido("P-7"));
        mvc.perform(post("/api/pedidos").header(U, "u1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/pedidos/P-7"))
                .andExpect(jsonPath("$.id").value("P-7"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE_PAGO"));
    }

    @Test
    void sucursalesNoRequiereUsuario() throws Exception {
        when(servicioCarrito.sucursales()).thenReturn(List.of());
        mvc.perform(get("/api/carrito/sucursales")).andExpect(status().isOk());
    }

    @Test
    void resumenAdmin200() throws Exception {
        when(servicioResumenAdmin.resumen()).thenReturn(new ResumenAdminDTO(List.of(), List.of(), List.of(), 0, 0, 0));
        mvc.perform(get("/api/admin/resumen")).andExpect(status().isOk()).andExpect(jsonPath("$.ingresosTotales").value(0));
    }

    @Test
    void corsPermiteLaTiendaYElHeaderDeUsuario() throws Exception {
        mvc.perform(options("/api/carrito").header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "X-Usuario-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"));
    }

    // ---------------------------------------------------------------- 404 / 409 / 422 / 503

    @Test
    void cuponInexistente404() throws Exception {
        when(servicioCarrito.aplicarCupon("u1", "NOEXISTE")).thenThrow(new RecursoNoEncontradoException("El cupón NOEXISTE no existe"));
        mvc.perform(post("/api/carrito/cupon").header(U, "u1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"NOEXISTE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No encontrado"));
    }

    @Test
    void cuponBajoElMinimo422() throws Exception {
        when(servicioCarrito.aplicarCupon("u1", "GAMER5000"))
                .thenThrow(new CuponNoAplicableException("El cupón GAMER5000 requiere una compra mínima de $50.000"));
        mvc.perform(post("/api/carrito/cupon").header(U, "u1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"GAMER5000\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detalle").value(containsString("$50.000")));
    }

    @Test
    void carritoVacioAlCrearPedido422() throws Exception {
        when(servicioPedidos.crearPedido("u1")).thenThrow(new ReglaNegocioException("El carrito está vacío"));
        mvc.perform(post("/api/pedidos").header(U, "u1")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sinStock409PropagaFaltantes() throws Exception {
        when(servicioPedidos.crearPedido("u1")).thenThrow(new StockInsuficienteException("No hay stock suficiente",
                List.of(new FaltanteDTO("item_110", 2))));
        mvc.perform(post("/api/pedidos").header(U, "u1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Stock insuficiente"))
                .andExpect(jsonPath("$.faltantes[0].productoId").value("item_110"))
                .andExpect(jsonPath("$.faltantes[0].disponible").value(2));
    }

    @Test
    void pedidoInexistente404() throws Exception {
        when(servicioPedidos.obtenerDelUsuario("u1", "P-999")).thenThrow(new RecursoNoEncontradoException("El pedido P-999 no existe"));
        mvc.perform(get("/api/pedidos/P-999").header(U, "u1")).andExpect(status().isNotFound());
    }

    @Test
    void productoInexistenteAlAgregar404() throws Exception {
        when(servicioCarrito.agregarItem(eq("u1"), any())).thenThrow(new RecursoNoEncontradoException("El producto item_999 no existe"));
        mvc.perform(post("/api/carrito/items").header(U, "u1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productoId\":\"item_999\",\"cantidad\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void msProductosCaido503() throws Exception {
        when(servicioCarrito.actualizarCantidad(anyString(), anyString(), anyInt()))
                .thenThrow(new ServicioNoDisponibleException("ms-productos no disponible"));
        mvc.perform(patch("/api/carrito/items/item_101").header(U, "u1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidad\":3}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void vaciarYQuitarCupon200() throws Exception {
        when(servicioCarrito.vaciar("u1")).thenReturn(carritoVacio("u1"));
        when(servicioCarrito.quitarCupon("u1")).thenReturn(carritoVacio("u1"));
        mvc.perform(delete("/api/carrito").header(U, "u1")).andExpect(status().isOk());
        mvc.perform(delete("/api/carrito/cupon").header(U, "u1")).andExpect(status().isOk());
    }
}
