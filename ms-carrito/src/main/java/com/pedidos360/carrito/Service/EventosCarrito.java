package com.pedidos360.carrito.Service;

import com.pedidos360.carrito.DTO.EventoInteraccionDTO;
import com.pedidos360.carrito.DTO.TipoEvento;
import com.pedidos360.carrito.Model.ItemCarrito;
import com.pedidos360.carrito.Model.ItemPedido;
import com.pedidos360.carrito.Model.MetodoEnvio;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Construye los EventoInteraccionDTO de §2.3 que publica ms-carrito (puro, testeable). */
public final class EventosCarrito {

    public static final String ORIGEN = "ms-carrito";

    private EventosCarrito() {
    }

    public static EventoInteraccionDTO agregarCarrito(String usuarioId, ItemCarrito item, int cantidadAgregada, Instant ahora) {
        Map<String, String> detalle = new LinkedHashMap<>();
        detalle.put("cantidad", String.valueOf(cantidadAgregada));
        detalle.put("precioUnitario", String.valueOf(item.getPrecioUnitario()));
        return base(usuarioId, TipoEvento.AGREGAR_CARRITO, ahora)
                .productoId(item.getProductoId())
                .categoria(item.getCategoria())
                .subcategoria(item.getSubcategoria())
                .marca(item.getMarca())
                .detalle(detalle)
                .build();
    }

    /** detalle.productos permite a ms-recomendaciones repartir el peso 0.5 entre lo que hay en el carrito. */
    public static EventoInteraccionDTO aplicarCupon(String usuarioId, String codigo, List<String> productos, Instant ahora) {
        Map<String, String> detalle = new LinkedHashMap<>();
        detalle.put("cupon", codigo);
        detalle.put("productos", String.join(",", productos));
        return base(usuarioId, TipoEvento.APLICAR_CUPON, ahora).detalle(detalle).build();
    }

    public static EventoInteraccionDTO gestionarEnvio(String usuarioId, MetodoEnvio metodo, String comuna,
                                                      String sucursal, Instant ahora) {
        Map<String, String> detalle = new LinkedHashMap<>();
        detalle.put("metodoEnvio", metodo.name());
        if (metodo == MetodoEnvio.ENVIO) {
            detalle.put("comuna", comuna);
        } else {
            detalle.put("sucursal", sucursal);
        }
        return base(usuarioId, TipoEvento.GESTIONAR_ENVIO, ahora).detalle(detalle).build();
    }

    public static EventoInteraccionDTO compra(String usuarioId, String idPedido, ItemPedido item, Instant ahora) {
        Map<String, String> detalle = new LinkedHashMap<>();
        detalle.put("idPedido", idPedido);
        detalle.put("cantidad", String.valueOf(item.getCantidad()));
        detalle.put("precioUnitario", String.valueOf(item.getPrecioUnitario()));
        return base(usuarioId, TipoEvento.COMPRA, ahora)
                .productoId(item.getProductoId())
                .categoria(item.getCategoria())
                .subcategoria(item.getSubcategoria())
                .marca(item.getMarca())
                .detalle(detalle)
                .build();
    }

    private static EventoInteraccionDTO.EventoInteraccionDTOBuilder base(String usuarioId, TipoEvento tipo, Instant ahora) {
        return EventoInteraccionDTO.builder()
                .idEvento(UUID.randomUUID().toString())
                .usuarioId(usuarioId)
                .tipoEvento(tipo)
                .origen(ORIGEN)
                .timestamp(ahora.toEpochMilli());
    }
}
