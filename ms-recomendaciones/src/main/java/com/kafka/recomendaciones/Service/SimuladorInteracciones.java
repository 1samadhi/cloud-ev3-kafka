package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.Component.ProductorInteracciones;
import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.ResultadoSimulacionDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Model.Producto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Generador de trafico para la demo (POST /api/interacciones/simular).
 *
 * No es azar plano: cada usuario simulado tiene una categoria favorita (70 % de
 * sus eventos caen ahi) y los tipos siguen un embudo realista (muchas vistas,
 * menos carritos, pocas compras). Asi el dashboard muestra un embudo creible y
 * las recomendaciones de cada usuario tienen un perfil que explicar.
 */
@Slf4j
@Service
public class SimuladorInteracciones {

    static final List<String> USUARIOS_DEMO = List.of("demo-1", "demo-2", "demo-3", "demo-4", "demo-5");

    private final ProductorInteracciones productor;
    private final CatalogoEnMemoria catalogo;
    private final Clock reloj;
    private final Random azar;

    /** @Autowired explicito: con dos constructores Spring no elige solo (el otro es para pruebas con semilla fija). */
    @Autowired
    public SimuladorInteracciones(ProductorInteracciones productor, CatalogoEnMemoria catalogo, Clock reloj) {
        this(productor, catalogo, reloj, new Random());
    }

    SimuladorInteracciones(ProductorInteracciones productor, CatalogoEnMemoria catalogo, Clock reloj, Random azar) {
        this.productor = productor;
        this.catalogo = catalogo;
        this.reloj = reloj;
        this.azar = azar;
    }

    public ResultadoSimulacionDTO simular(int cantidad, String usuarioId) {
        List<String> usuarios = usuarioId == null || usuarioId.isBlank() ? USUARIOS_DEMO : List.of(usuarioId.trim());
        List<Producto> productos = new ArrayList<>(catalogo.obtenerTodos());
        List<String> categorias = productos.stream().map(Producto::getCategoria).distinct().sorted().toList();

        Map<TipoEvento, Integer> conteo = new EnumMap<>(TipoEvento.class);
        for (int i = 0; i < cantidad; i++) {
            String usuario = usuarios.get(azar.nextInt(usuarios.size()));
            // Favorita estable por usuario: el mismo demo-3 "prefiere" siempre lo mismo.
            String favorita = categorias.get(Math.floorMod(usuario.hashCode(), categorias.size()));
            Producto producto = elegirProducto(productos, favorita);
            EventoInteraccionDTO evento = crearEvento(usuario, producto);
            productor.publicar(evento);
            conteo.merge(evento.getTipoEvento(), 1, Integer::sum);
        }

        Map<String, Integer> porTipo = new LinkedHashMap<>();
        conteo.forEach((k, v) -> porTipo.put(k.name(), v));
        log.info("Simulacion: {} eventos publicados para {} -> {}", cantidad, usuarios, porTipo);
        return new ResultadoSimulacionDTO(cantidad, usuarios, porTipo);
    }

    private Producto elegirProducto(List<Producto> productos, String favorita) {
        if (azar.nextDouble() < 0.7) {
            List<Producto> deFavorita = productos.stream().filter(p -> favorita.equals(p.getCategoria())).toList();
            if (!deFavorita.isEmpty()) {
                return deFavorita.get(azar.nextInt(deFavorita.size()));
            }
        }
        return productos.get(azar.nextInt(productos.size()));
    }

    private EventoInteraccionDTO crearEvento(String usuario, Producto producto) {
        double tirada = azar.nextDouble();
        TipoEvento tipo;
        if (tirada < 0.65) {
            tipo = TipoEvento.VER_PRODUCTO;
        } else if (tirada < 0.85) {
            tipo = TipoEvento.AGREGAR_CARRITO;
        } else if (tirada < 0.93) {
            tipo = TipoEvento.COMPRA;
        } else if (tirada < 0.97) {
            tipo = TipoEvento.APLICAR_CUPON;
        } else {
            tipo = TipoEvento.GESTIONAR_ENVIO;
        }

        Map<String, String> detalle = new HashMap<>();
        detalle.put("simulado", "true");
        EventoInteraccionDTO.EventoInteraccionDTOBuilder b = EventoInteraccionDTO.builder()
                .idEvento(UUID.randomUUID().toString())
                .usuarioId(usuario)
                .tipoEvento(tipo)
                .origen(TraductorInteracciones.ORIGEN)
                .timestamp(reloj.millis());

        switch (tipo) {
            case APLICAR_CUPON -> {
                // Igual que ms-carrito: sin productoId, con los productos del carrito en detalle.
                detalle.put("cupon", "BIENVENIDA10");
                detalle.put("productos", producto.getId());
            }
            case GESTIONAR_ENVIO -> {
                detalle.put("metodoEnvio", azar.nextBoolean() ? "ENVIO" : "RETIRO");
                detalle.put("comuna", "Providencia");
            }
            default -> b.productoId(producto.getId())
                    .categoria(producto.getCategoria())
                    .subcategoria(producto.getSubcategoria())
                    .marca(producto.getMarca());
        }
        if (tipo == TipoEvento.AGREGAR_CARRITO || tipo == TipoEvento.COMPRA) {
            detalle.put("cantidad", "1");
        }
        return b.detalle(detalle).build();
    }
}
