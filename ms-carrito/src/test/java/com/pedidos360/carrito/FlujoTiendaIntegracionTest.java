package com.pedidos360.carrito;

import com.pedidos360.carrito.Component.ClienteProductos;
import com.pedidos360.carrito.Component.ProductorInteracciones;
import com.pedidos360.carrito.Component.PublicadorNotificaciones;
import com.pedidos360.carrito.DTO.EstadoPago;
import com.pedidos360.carrito.DTO.EventoInteraccionDTO;
import com.pedidos360.carrito.DTO.FaltanteDTO;
import com.pedidos360.carrito.DTO.Pasarela;
import com.pedidos360.carrito.DTO.ProductoDTO;
import com.pedidos360.carrito.DTO.ResultadoPagoDTO;
import com.pedidos360.carrito.DTO.TipoEvento;
import com.pedidos360.carrito.DTO.TipoNotificacion;
import com.pedidos360.carrito.Exception.StockInsuficienteException;
import com.pedidos360.carrito.Service.ServicioPedidos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Flujo completo de la tienda contra el contexto Spring real (H2, transacciones, controllers,
 * manejador de errores, semilla de cupones). Sólo se mockean los bordes: ms-productos, Kafka y
 * RabbitMQ. Deja las respuestas JSON reales en target/ejemplos-json/ como referencia para la tienda.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FlujoTiendaIntegracionTest {

    static final Path SALIDA = Path.of("target", "ejemplos-json");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ServicioPedidos servicioPedidos;

    @MockitoBean ClienteProductos clienteProductos;
    @MockitoBean ProductorInteracciones productorInteracciones;
    @MockitoBean PublicadorNotificaciones publicadorNotificaciones;

    private JsonNode llamar(String nombre, MockHttpServletRequestBuilder req, int estadoEsperado) throws Exception {
        ResultActions r = mvc.perform(req.header("X-Usuario-Id", "cliente-demo"));
        var resp = r.andReturn().getResponse();
        String cuerpo = resp.getContentAsString(StandardCharsets.UTF_8);
        assertThat(resp.getStatus()).as(nombre + " → " + cuerpo).isEqualTo(estadoEsperado);
        Files.createDirectories(SALIDA);
        JsonNode nodo = json.readTree(cuerpo);
        Files.writeString(SALIDA.resolve(nombre + ".json"),
                "// HTTP " + resp.getStatus() + "\n" + json.writerWithDefaultPrettyPrinter().writeValueAsString(nodo),
                StandardCharsets.UTF_8);
        return nodo;
    }

    private static MockHttpServletRequestBuilder conJson(MockHttpServletRequestBuilder req, String cuerpo) {
        return req.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    @Test
    void compraCompletaDesdeElCarritoHastaElPago() throws Exception {
        when(clienteProductos.obtenerProducto("item_112"))
                .thenReturn(new ProductoDTO("item_112", "DualSense", "Sony", "gaming", "controles", 74_990L, 70));
        when(clienteProductos.obtenerProducto("item_117"))
                .thenReturn(new ProductoDTO("item_117", "Cargador GaN 65 W", "Anker", "accesorios", "carga", 34_990L, 80));

        llamar("01-get-carrito-vacio", get("/api/carrito"), 200);
        llamar("02-post-items", conJson(post("/api/carrito/items"), "{\"productoId\":\"item_112\",\"cantidad\":1}"), 200);
        JsonNode c = llamar("03-post-items-segundo", conJson(post("/api/carrito/items"),
                "{\"productoId\":\"item_117\",\"cantidad\":1}"), 200);
        assertThat(c.get("subtotal").asLong()).isEqualTo(109_980);

        // Kafka AGREGAR_CARRITO y RabbitMQ carrito.producto-agregado, publicados después del commit.
        ArgumentCaptor<EventoInteraccionDTO> eventos = ArgumentCaptor.forClass(EventoInteraccionDTO.class);
        verify(productorInteracciones, atLeastOnce()).publicar(eventos.capture());
        assertThat(eventos.getAllValues()).extracting(EventoInteraccionDTO::getTipoEvento).contains(TipoEvento.AGREGAR_CARRITO);
        verify(publicadorNotificaciones, atLeastOnce()).publicar(eq(TipoNotificacion.CARRITO_PRODUCTO_AGREGADO),
                eq("cliente-demo"), anyString(), anyString(), isNull(), anyMap());

        llamar("04-post-cupon-404", conJson(post("/api/carrito/cupon"), "{\"codigo\":\"NOEXISTE\"}"), 404);
        c = llamar("05-post-cupon-bienvenida10", conJson(post("/api/carrito/cupon"), "{\"codigo\":\"bienvenida10\"}"), 200);
        assertThat(c.get("descuento").asLong()).isEqualTo(10_998);

        llamar("06-put-envio-400", conJson(put("/api/carrito/envio"), "{\"metodo\":\"ENVIO\"}"), 400);
        c = llamar("07-put-envio-retiro", conJson(put("/api/carrito/envio"),
                "{\"metodo\":\"RETIRO\",\"sucursal\":\"Maipú\"}"), 200);
        assertThat(c.get("sucursal").asText()).isEqualTo("Maipú · Av. Pajaritos 3450");
        c = llamar("08-put-envio-domicilio", conJson(put("/api/carrito/envio"),
                "{\"metodo\":\"ENVIO\",\"direccion\":\"Av. Siempre Viva 742 (depto 12)\",\"comuna\":\"Ñuñoa\"}"), 200);
        // subtotal 109 980 ≥ 100 000 → despacho gratis; total = 109 980 − 10 998.
        assertThat(c.get("costoEnvio").asLong()).isZero();
        assertThat(c.get("total").asLong()).isEqualTo(98_982);

        JsonNode pedido = llamar("09-post-pedidos-201", post("/api/pedidos"), 201);
        String id = pedido.get("id").asText();
        assertThat(pedido.get("estado").asText()).isEqualTo("PENDIENTE_PAGO");
        assertThat(pedido.get("total").asLong()).isEqualTo(98_982);
        llamar("10-get-carrito-tras-pedido", get("/api/carrito"), 200);

        // Lo que haría el consumidor de pagos-resultado al recibir el AUTORIZADO de ms-pago.
        servicioPedidos.procesarResultadoPago(ResultadoPagoDTO.builder().idOrden(id).idCliente("cliente-demo")
                .estado(EstadoPago.AUTORIZADO).monto(98_982L).codigoAutorizacion("1213").ultimos4("6623")
                .pasarela(Pasarela.SIMULADA).timestamp(System.currentTimeMillis()).build());
        JsonNode pagado = llamar("11-get-pedido-pagado", get("/api/pedidos/" + id), 200);
        assertThat(pagado.get("estado").asText()).isEqualTo("PAGADO");
        assertThat(pagado.get("historial")).hasSize(2);

        llamar("12-get-pedidos", get("/api/pedidos"), 200);
        JsonNode resumen = llamar("13-get-admin-resumen", get("/api/admin/resumen"), 200);
        assertThat(resumen.get("ingresosTotales").asLong()).isEqualTo(98_982);
        assertThat(resumen.get("unidadesVendidas").asLong()).isEqualTo(2);
        assertThat(resumen.get("ventasPorDia")).hasSize(14);
        llamar("14-get-admin-pedidos", get("/api/admin/pedidos"), 200);
        llamar("15-get-pedido-ajeno-404", get("/api/pedidos/P-999999"), 404);
        llamar("16-get-sucursales", get("/api/carrito/sucursales"), 200);
    }

    @Test
    void erroresDeNegocioConSuFormaJson() throws Exception {
        when(clienteProductos.obtenerProducto("item_110"))
                .thenReturn(new ProductoDTO("item_110", "PlayStation 5", "Sony", "gaming", "consolas", 549_990L, 5));
        llamar("20-post-pedidos-carrito-vacio-422", post("/api/pedidos").header("X-Usuario-Id", "otro"), 422);

        mvc.perform(conJson(post("/api/carrito/items"), "{\"productoId\":\"item_110\",\"cantidad\":3}")
                .header("X-Usuario-Id", "cliente-sin-stock"));
        mvc.perform(conJson(put("/api/carrito/envio"), "{\"metodo\":\"RETIRO\",\"sucursal\":\"Providencia\"}")
                .header("X-Usuario-Id", "cliente-sin-stock"));
        doThrow(new StockInsuficienteException("No hay stock suficiente para uno o más productos",
                List.of(new FaltanteDTO("item_110", 2)))).when(clienteProductos).reservarStock(any());
        var r = mvc.perform(post("/api/pedidos").header("X-Usuario-Id", "cliente-sin-stock")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(409);
        Files.createDirectories(SALIDA);
        Files.writeString(SALIDA.resolve("21-post-pedidos-409-faltantes.json"),
                "// HTTP 409\n" + json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(r.getContentAsString(StandardCharsets.UTF_8))));
        assertThat(json.readTree(r.getContentAsString(StandardCharsets.UTF_8)).get("faltantes").get(0).get("disponible").asInt())
                .isEqualTo(2);

        var cupon = mvc.perform(conJson(post("/api/carrito/cupon"), "{\"codigo\":\"GAMER5000\"}")
                .header("X-Usuario-Id", "cliente-pobre")).andReturn().getResponse();
        assertThat(cupon.getStatus()).isEqualTo(422);
        Files.writeString(SALIDA.resolve("22-post-cupon-422-minimo.json"),
                "// HTTP 422\n" + json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(cupon.getContentAsString(StandardCharsets.UTF_8))));
    }
}
