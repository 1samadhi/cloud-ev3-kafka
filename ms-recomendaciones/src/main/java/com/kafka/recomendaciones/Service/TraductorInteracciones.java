package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.InteractionRequestsDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Exception.PayloadInvalidoException;
import com.kafka.recomendaciones.Model.Producto;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Traduce el cuerpo viejo de POST /api/interacciones ({clientId, itemId, action,
 * category, productType}) al EventoInteraccionDTO del contrato.
 *
 * Existe para no romper la consola mientras el resto del sistema ya habla el
 * contrato nuevo: por Kafka solo viaja el DTO nuevo.
 */
@Component
public class TraductorInteracciones {

    public static final String ORIGEN = "ms-recomendaciones";

    private final CatalogoEnMemoria catalogo;
    private final Clock reloj;

    public TraductorInteracciones(CatalogoEnMemoria catalogo, Clock reloj) {
        this.catalogo = catalogo;
        this.reloj = reloj;
    }

    public EventoInteraccionDTO traducir(InteractionRequestsDTO peticion) {
        TipoEvento tipo = tipoDesdeAccion(peticion.getAction());
        Optional<Producto> producto = catalogo.buscarPorId(peticion.getItemId());

        // Atributos del catalogo cuando el producto existe: la consola manda las
        // categorias de su mapa local (electronica/audio...) que ya no coinciden con
        // §7.1, y el motor compara contra el catalogo. Si el producto no esta, se
        // respeta lo que vino (category -> categoria, productType -> subcategoria).
        String categoria = producto.map(Producto::getCategoria).orElse(vacioANull(peticion.getCategory()));
        String subcategoria = producto.map(Producto::getSubcategoria).orElse(vacioANull(peticion.getProductType()));
        String marca = producto.map(Producto::getMarca).orElse(null);

        Map<String, String> detalle = new HashMap<>();
        // Rastro del formato viejo: permite distinguir en el historial lo que vino
        // de la consola sin agregar campos al contrato.
        detalle.put("accionOriginal", peticion.getAction());

        return EventoInteraccionDTO.builder()
                .idEvento(UUID.randomUUID().toString())
                .usuarioId(peticion.getClientId().trim())
                .tipoEvento(tipo)
                .productoId(peticion.getItemId().trim())
                .categoria(categoria)
                .subcategoria(subcategoria)
                .marca(marca)
                .origen(ORIGEN)
                .detalle(detalle)
                .timestamp(reloj.millis())
                .build();
    }

    /**
     * view -> VER_PRODUCTO, cart -> AGREGAR_CARRITO, purchase -> COMPRA. Tambien
     * acepta el nombre nuevo (VER_PRODUCTO...) por si un cliente ya migro.
     */
    public static TipoEvento tipoDesdeAccion(String accion) {
        if (accion == null || accion.isBlank()) {
            throw new PayloadInvalidoException("action es obligatorio (view, cart o purchase)");
        }
        String normalizada = accion.trim().toLowerCase(Locale.ROOT);
        return switch (normalizada) {
            case "view" -> TipoEvento.VER_PRODUCTO;
            case "cart" -> TipoEvento.AGREGAR_CARRITO;
            case "purchase" -> TipoEvento.COMPRA;
            default -> {
                try {
                    TipoEvento t = TipoEvento.valueOf(accion.trim().toUpperCase(Locale.ROOT));
                    if (!t.requiereProducto()) {
                        // Cupon y envio no tienen producto: este endpoint siempre trae itemId,
                        // asi que no es el canal para esos eventos (los publica ms-carrito).
                        throw new PayloadInvalidoException("action " + accion + " no se acepta en este endpoint");
                    }
                    yield t;
                } catch (IllegalArgumentException e) {
                    throw new PayloadInvalidoException("action desconocida: '" + accion
                            + "'. Valores validos: view, cart, purchase");
                }
            }
        };
    }

    private static String vacioANull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
