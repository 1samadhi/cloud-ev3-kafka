package com.pedidos360.carrito.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.carrito.DTO.FaltanteDTO;
import com.pedidos360.carrito.DTO.LineaStockDTO;
import com.pedidos360.carrito.DTO.MovimientoStockDTO;
import com.pedidos360.carrito.DTO.ProductoDTO;
import com.pedidos360.carrito.Exception.RecursoNoEncontradoException;
import com.pedidos360.carrito.Exception.ServicioNoDisponibleException;
import com.pedidos360.carrito.Exception.StockInsuficienteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Cliente REST de ms-productos contra MockRestServiceServer. Los cuerpos son los REALES de
 * ms-productos (ProductoDTO con categoriaEtiqueta/actualizadoEn, ErrorDTO con faltantes,
 * RespuestaReservaDTO).
 */
class ClienteProductosTest {

    MockRestServiceServer servidor;
    ClienteProductos cliente;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ms-productos");
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new ClienteProductos(builder.build(), new ObjectMapper());
    }

    @Test
    void obtieneElProductoIgnorandoCamposExtra() {
        servidor.expect(requestTo("http://ms-productos/api/productos/item_101")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"item_101","nombre":"Galaxy S24","marca":"Samsung","categoria":"celulares",
                         "categoriaEtiqueta":"Celulares","subcategoria":"gama-alta","precio":649990,"stock":14,
                         "calificacion":4.7,"imagenUrl":null,"descripcion":"6.2\\" AMOLED","actualizadoEn":1764082800000}
                        """, MediaType.APPLICATION_JSON));
        ProductoDTO p = cliente.obtenerProducto("item_101");
        assertThat(p).isEqualTo(new ProductoDTO("item_101", "Galaxy S24", "Samsung", "celulares", "gama-alta", 649_990L, 14));
        servidor.verify();
    }

    @Test
    void productoInexistenteEs404() {
        servidor.expect(requestTo("http://ms-productos/api/productos/item_999"))
                .andRespond(withResourceNotFound().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Producto no encontrado\",\"detalle\":\"item_999\",\"timestamp\":1}"));
        assertThatThrownBy(() -> cliente.obtenerProducto("item_999")).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void reservaEnviaElCuerpoDelContratoYAcepta200() {
        servidor.expect(requestTo("http://ms-productos/api/productos/stock/reservas"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json("{\"idPedido\":\"P-7\",\"items\":[{\"productoId\":\"item_110\",\"cantidad\":3}]}", true))
                .andRespond(withSuccess("{\"idPedido\":\"P-7\",\"resultado\":\"RESERVADA\",\"items\":[],\"timestamp\":1}",
                        MediaType.APPLICATION_JSON));
        assertThatCode(() -> cliente.reservarStock(new MovimientoStockDTO("P-7", List.of(new LineaStockDTO("item_110", 3)))))
                .doesNotThrowAnyException();
        servidor.verify();
    }

    @Test
    void reserva409TraeLosFaltantes() {
        servidor.expect(requestTo("http://ms-productos/api/productos/stock/reservas"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":"Stock insuficiente","detalle":"La reserva se rechazó completa: ningún producto fue descontado",
                         "faltantes":[{"productoId":"item_110","disponible":2},{"productoId":"item_119","disponible":0}],
                         "timestamp":1764082800000}
                        """));
        assertThatThrownBy(() -> cliente.reservarStock(new MovimientoStockDTO("P-8",
                List.of(new LineaStockDTO("item_110", 3), new LineaStockDTO("item_119", 1)))))
                .isInstanceOf(StockInsuficienteException.class)
                .satisfies(e -> assertThat(((StockInsuficienteException) e).getFaltantes()).containsExactly(
                        new FaltanteDTO("item_110", 2), new FaltanteDTO("item_119", 0)));
    }

    @Test
    void conflicto409SinFaltantesPropagaElDetalle() {
        servidor.expect(requestTo("http://ms-productos/api/productos/stock/reservas"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Conflicto de reserva\",\"detalle\":\"El pedido P-9 ya fue liberado\",\"timestamp\":1}"));
        assertThatThrownBy(() -> cliente.reservarStock(new MovimientoStockDTO("P-9", List.of(new LineaStockDTO("item_101", 1)))))
                .isInstanceOf(StockInsuficienteException.class)
                .hasMessage("El pedido P-9 ya fue liberado")
                .satisfies(e -> assertThat(((StockInsuficienteException) e).getFaltantes()).isEmpty());
    }

    @Test
    void errorDelServidorEs503ParaQuienLlama() {
        servidor.expect(requestTo("http://ms-productos/api/productos/stock/reservas")).andRespond(withServerError());
        assertThatThrownBy(() -> cliente.reservarStock(new MovimientoStockDTO("P-10", List.of(new LineaStockDTO("item_101", 1)))))
                .isInstanceOf(ServicioNoDisponibleException.class);
    }

    @Test
    void liberacionIdempotenteSinReservaEs200() {
        servidor.expect(requestTo("http://ms-productos/api/productos/stock/liberaciones")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"idPedido\":\"P-11\",\"resultado\":\"SIN_RESERVA\",\"items\":[],\"timestamp\":1}",
                        MediaType.APPLICATION_JSON));
        assertThatCode(() -> cliente.liberarStock(new MovimientoStockDTO("P-11", List.of(new LineaStockDTO("item_101", 1)))))
                .doesNotThrowAnyException();
        servidor.verify();
    }

    @Test
    void liberacionConMsProductosCaidoLanzaParaQueKafkaReintente() {
        servidor.expect(requestTo("http://ms-productos/api/productos/stock/liberaciones"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> cliente.liberarStock(new MovimientoStockDTO("P-12", List.of(new LineaStockDTO("item_101", 1)))))
                .isInstanceOf(ServicioNoDisponibleException.class);
    }
}
