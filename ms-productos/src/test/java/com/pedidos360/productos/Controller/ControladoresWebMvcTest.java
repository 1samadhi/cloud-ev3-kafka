package com.pedidos360.productos.Controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.pedidos360.productos.Component.UsuarioActual;
import com.pedidos360.productos.DTO.FaltanteDTO;
import com.pedidos360.productos.DTO.NivelStock;
import com.pedidos360.productos.DTO.ProductoDTO;
import com.pedidos360.productos.DTO.StockAdminDTO;
import com.pedidos360.productos.Exception.ProductoNoEncontradoException;
import com.pedidos360.productos.Exception.StockInsuficienteException;
import com.pedidos360.productos.Service.ServicioCatalogo;
import com.pedidos360.productos.Service.ServicioStock;

/** Validación, códigos HTTP y forma de los errores, con los servicios simulados. */
@WebMvcTest({ProductoController.class, StockController.class})
@Import(UsuarioActual.class)
class ControladoresWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServicioCatalogo servicioCatalogo;

    @MockitoBean
    private ServicioStock servicioStock;

    private static ProductoDTO galaxy() {
        return ProductoDTO.builder().id("item_101").nombre("Galaxy S24").marca("Samsung").categoria("celulares")
                .categoriaEtiqueta("Celulares").subcategoria("gama-alta").precio(649990L).stock(14).calificacion(4.7)
                .descripcion("6.2\" AMOLED, 256 GB.").actualizadoEn(1L).build();
    }

    @Test
    void detalleConUsuarioDelegaConElUsuarioDelHeader() throws Exception {
        when(servicioCatalogo.obtenerDetalle(eq("item_101"), any())).thenReturn(galaxy());

        mvc.perform(get("/api/productos/item_101").header("X-Usuario-Id", "u-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Galaxy S24"));
        verify(servicioCatalogo).obtenerDetalle("item_101", Optional.of("u-1"));
    }

    @Test
    void detalleAnonimoDelegaSinUsuario() throws Exception {
        when(servicioCatalogo.obtenerDetalle(eq("item_101"), any())).thenReturn(galaxy());
        mvc.perform(get("/api/productos/item_101")).andExpect(status().isOk());
        verify(servicioCatalogo).obtenerDetalle("item_101", Optional.empty());
    }

    @Test
    void headerDeUsuarioMalFormadoEs400YNoLlegaAlServicio() throws Exception {
        mvc.perform(get("/api/productos/item_101").header("X-Usuario-Id", "u 1\r\nfalso"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Petición inválida"))
                .andExpect(jsonPath("$.detalle", containsString("X-Usuario-Id")));
        verify(servicioCatalogo, never()).obtenerDetalle(anyString(), any());
    }

    @Test
    void productoInexistenteEs404ConFormaDeError() throws Exception {
        when(servicioCatalogo.obtenerDetalle(eq("item_999"), any())).thenThrow(new ProductoNoEncontradoException("item_999"));
        mvc.perform(get("/api/productos/item_999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Producto no encontrado"))
                .andExpect(jsonPath("$.detalle").value("No existe el producto item_999"))
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void ordenDesconocidoEs400() throws Exception {
        mvc.perform(get("/api/productos").param("orden", "barato"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle", containsString("precio_asc")));
    }

    @Test
    void reservaSinItemsNiPedidoEs400ConDetalleDeCampos() throws Exception {
        mvc.perform(post("/api/productos/stock/reservas").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idPedido\":\"\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle", containsString("idPedido")))
                .andExpect(jsonPath("$.detalle", containsString("items")));
        verify(servicioStock, never()).reservar(any());
    }

    @Test
    void reservaConCantidadCeroEs400() throws Exception {
        mvc.perform(post("/api/productos/stock/reservas").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idPedido\":\"P-1\",\"items\":[{\"productoId\":\"item_101\",\"cantidad\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalle", containsString("items[0].cantidad")));
    }

    @Test
    void jsonMalFormadoEs400() throws Exception {
        mvc.perform(post("/api/productos/stock/reservas").contentType(MediaType.APPLICATION_JSON).content("{idPedido:"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Petición mal formada"));
    }

    @Test
    void stockInsuficienteEs409ConFaltantes() throws Exception {
        when(servicioStock.reservar(any())).thenThrow(new StockInsuficienteException(List.of(new FaltanteDTO("item_110", 2))));
        mvc.perform(post("/api/productos/stock/reservas").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idPedido\":\"P-1\",\"items\":[{\"productoId\":\"item_110\",\"cantidad\":3}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Stock insuficiente"))
                .andExpect(jsonPath("$.faltantes[0].productoId").value("item_110"))
                .andExpect(jsonPath("$.faltantes[0].disponible").value(2));
    }

    @Test
    void reposicionConCantidadNegativaEs400() throws Exception {
        mvc.perform(put("/api/productos/item_101/stock").contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":-3}"))
                .andExpect(status().isBadRequest());
        verify(servicioStock, never()).reponer(anyString(), anyInt());
    }

    @Test
    void stockAdminDevuelveNivel() throws Exception {
        when(servicioCatalogo.stockAdmin()).thenReturn(List.of(new StockAdminDTO("item_110", "PlayStation 5", 0, NivelStock.AGOTADO)));
        mvc.perform(get("/api/productos/admin/stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nivel").value("AGOTADO"));
    }

    @Test
    void preflightCorsPermiteElHeaderDeUsuarioDesdeLaTienda() throws Exception {
        mvc.perform(options("/api/productos/item_101")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Usuario-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Usuario-Id")));
    }
}
