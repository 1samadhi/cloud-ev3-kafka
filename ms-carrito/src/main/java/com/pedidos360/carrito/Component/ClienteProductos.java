package com.pedidos360.carrito.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.carrito.DTO.ConflictoStockDTO;
import com.pedidos360.carrito.DTO.FaltanteDTO;
import com.pedidos360.carrito.DTO.MovimientoStockDTO;
import com.pedidos360.carrito.DTO.ProductoDTO;
import com.pedidos360.carrito.Exception.RecursoNoEncontradoException;
import com.pedidos360.carrito.Exception.ServicioNoDisponibleException;
import com.pedidos360.carrito.Exception.StockInsuficienteException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * Cliente REST de ms-productos (§7.2). REST y no Kafka porque reservar stock es un comando que
 * necesita respuesta inmediata (sí/no con faltantes) antes de crear el pedido.
 *
 * No reenvía X-Usuario-Id al consultar un producto: ms-productos publica VER_PRODUCTO cuando
 * recibe ese header, y una consulta interna de precio no es una visita del usuario.
 */
@Slf4j
@Component
public class ClienteProductos {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ClienteProductos(@Qualifier("productosRestClient") RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public ProductoDTO obtenerProducto(String productoId) {
        try {
            ProductoDTO producto = restClient.get()
                    .uri("/api/productos/{id}", productoId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(ProductoDTO.class);
            if (producto == null || producto.precio() == null || producto.nombre() == null) {
                throw new ServicioNoDisponibleException("ms-productos respondió sin precio/nombre para " + productoId);
            }
            return producto;
        } catch (HttpClientErrorException.NotFound e) {
            throw new RecursoNoEncontradoException("El producto " + productoId + " no existe");
        } catch (RestClientException e) {
            throw noDisponible("consultar el producto " + productoId, e);
        }
    }

    /** Todo o nada (lo garantiza ms-productos). 409 → StockInsuficienteException con los faltantes. */
    public void reservarStock(MovimientoStockDTO reserva) {
        try {
            restClient.post()
                    .uri("/api/productos/stock/reservas")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(reserva)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Stock reservado en ms-productos para el pedido {} ({} líneas)",
                    reserva.idPedido(), reserva.items().size());
        } catch (HttpClientErrorException.Conflict e) {
            ConflictoStockDTO cuerpo = leerConflicto(e);
            List<FaltanteDTO> faltantes = cuerpo.faltantes() == null ? List.of() : cuerpo.faltantes();
            log.warn("ms-productos rechazó la reserva del pedido {}: faltantes={} detalle={}",
                    reserva.idPedido(), faltantes, cuerpo.detalle());
            // Sin faltantes el 409 es un conflicto de la reserva, no de stock: se propaga su detalle.
            String mensaje = faltantes.isEmpty() && cuerpo.detalle() != null
                    ? cuerpo.detalle()
                    : "No hay stock suficiente para uno o más productos";
            throw new StockInsuficienteException(mensaje, faltantes);
        } catch (RestClientException e) {
            throw noDisponible("reservar stock del pedido " + reserva.idPedido(), e);
        }
    }

    /**
     * Idempotente por idPedido en ms-productos: reintentarla (Kafka reintenta si esto lanza) no
     * devuelve el stock dos veces.
     */
    public void liberarStock(MovimientoStockDTO liberacion) {
        try {
            restClient.post()
                    .uri("/api/productos/stock/liberaciones")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(liberacion)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Stock liberado en ms-productos para el pedido {}", liberacion.idPedido());
        } catch (HttpClientErrorException.NotFound e) {
            // Nada que liberar (reserva inexistente o ya liberada): el objetivo ya se cumple.
            log.warn("ms-productos no encontró reserva que liberar para el pedido {}", liberacion.idPedido());
        } catch (RestClientException e) {
            throw noDisponible("liberar stock del pedido " + liberacion.idPedido(), e);
        }
    }

    private ConflictoStockDTO leerConflicto(RestClientResponseException e) {
        try {
            return objectMapper.readValue(e.getResponseBodyAsString(), ConflictoStockDTO.class);
        } catch (Exception ex) {
            log.warn("No se pudo leer el detalle del 409 de ms-productos: {}", ex.getMessage());
            return new ConflictoStockDTO(null, null, List.of());
        }
    }

    private ServicioNoDisponibleException noDisponible(String operacion, RestClientException e) {
        String motivo = e instanceof ResourceAccessException ? "sin conexión o timeout" : e.getMessage();
        return new ServicioNoDisponibleException("ms-productos no disponible al " + operacion + ": " + motivo, e);
    }
}
