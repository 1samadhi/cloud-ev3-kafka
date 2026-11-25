package com.pedidos360.productos.Controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.pedidos360.productos.PruebaIntegracionBase;

/**
 * Endpoints de punta a punta (HTTP → servicio → H2). Además guarda las respuestas reales en
 * target/ejemplos-respuestas.md: es el material de referencia para quien construye la tienda.
 */
class CatalogoIntegracionTest extends PruebaIntegracionBase {

    private static final Map<String, String> EJEMPLOS = new LinkedHashMap<>();

    private ResultActions guardar(String titulo, ResultActions r) throws Exception {
        EJEMPLOS.put(titulo, r.andReturn().getResponse().getStatus() + " " + r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        return r;
    }

    @AfterAll
    static void escribirEjemplos() throws Exception {
        StringBuilder sb = new StringBuilder("# Respuestas reales de ms-productos (generado por CatalogoIntegracionTest)\n\n");
        EJEMPLOS.forEach((k, v) -> sb.append("## ").append(k).append("\n\n```\n").append(v).append("\n```\n\n"));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "ejemplos-respuestas.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void listarPorCategoriaOrdenadoPorPrecio() throws Exception {
        guardar("GET /api/productos?categoria=audio&orden=precio_asc",
                mvc.perform(get("/api/productos").param("categoria", "audio").param("orden", "precio_asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value("item_109"))
                .andExpect(jsonPath("$[1].id").value("item_108"))
                .andExpect(jsonPath("$[2].id").value("item_107"))
                .andExpect(jsonPath("$[0].categoriaEtiqueta").value("Audio"));
    }

    @Test
    void listarTodoDevuelveLos20ProductosDeLaSemilla() throws Exception {
        mvc.perform(get("/api/productos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                // imagenUrl = null a propósito (§7.1): la tienda muestra un placeholder.
                .andExpect(jsonPath("$[0].imagenUrl").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void buscarSinTildesEncuentraCamaras() throws Exception {
        guardar("GET /api/productos?q=camaras",
                mvc.perform(get("/api/productos").param("q", "camaras")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("item_119"))
                .andExpect(jsonPath("$[1].id").value("item_120"));
    }

    @Test
    void categoriasConCantidad() throws Exception {
        guardar("GET /api/productos/categorias", mvc.perform(get("/api/productos/categorias")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].slug").value("celulares"))
                .andExpect(jsonPath("$[0].etiqueta").value("Celulares"))
                .andExpect(jsonPath("$[0].cantidad").value(3))
                .andExpect(jsonPath("$[4].etiqueta").value("TV y video"));
    }

    @Test
    void detalleYErrores() throws Exception {
        guardar("GET /api/productos/item_101 (X-Usuario-Id: u-123)",
                mvc.perform(get("/api/productos/item_101").header("X-Usuario-Id", "u-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Galaxy S24"))
                .andExpect(jsonPath("$.precio").value(649990))
                .andExpect(jsonPath("$.stock").value(14));
        guardar("GET /api/productos/item_999", mvc.perform(get("/api/productos/item_999")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Producto no encontrado"));
    }

    @Test
    void flujoDeReservaLiberacionReposicionYAdmin() throws Exception {
        String ok = "{\"idPedido\":\"P-1001\",\"items\":[{\"productoId\":\"item_110\",\"cantidad\":1},{\"productoId\":\"item_112\",\"cantidad\":2}]}";
        guardar("POST /api/productos/stock/reservas " + ok,
                mvc.perform(post("/api/productos/stock/reservas").contentType(MediaType.APPLICATION_JSON).content(ok)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("RESERVADA"));
        guardar("POST /api/productos/stock/reservas (repetida, mismo idPedido)",
                mvc.perform(post("/api/productos/stock/reservas").contentType(MediaType.APPLICATION_JSON).content(ok)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("YA_RESERVADA"));

        String sinStock = "{\"idPedido\":\"P-1002\",\"items\":[{\"productoId\":\"item_110\",\"cantidad\":10},{\"productoId\":\"item_101\",\"cantidad\":1}]}";
        guardar("POST /api/productos/stock/reservas " + sinStock,
                mvc.perform(post("/api/productos/stock/reservas").contentType(MediaType.APPLICATION_JSON).content(sinStock)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.faltantes[0].productoId").value("item_110"))
                .andExpect(jsonPath("$.faltantes[0].disponible").value(4));

        guardar("POST /api/productos/stock/reservas {\"idPedido\":\"\",\"items\":[]}",
                mvc.perform(post("/api/productos/stock/reservas").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idPedido\":\"\",\"items\":[]}")))
                .andExpect(status().isBadRequest());

        guardar("POST /api/productos/stock/liberaciones " + ok,
                mvc.perform(post("/api/productos/stock/liberaciones").contentType(MediaType.APPLICATION_JSON).content(ok)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("LIBERADA"));
        guardar("POST /api/productos/stock/liberaciones (repetida)",
                mvc.perform(post("/api/productos/stock/liberaciones").contentType(MediaType.APPLICATION_JSON).content(ok)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("YA_LIBERADA"));

        guardar("PUT /api/productos/item_110/stock {\"cantidad\":5}",
                mvc.perform(put("/api/productos/item_110/stock").contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":5}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(10));

        guardar("GET /api/productos/admin/stock", mvc.perform(get("/api/productos/admin/stock")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[0].id").value("item_102"))
                .andExpect(jsonPath("$[0].nivel").value("BAJO"))
                .andExpect(jsonPath("$[?(@.id=='item_117')].nivel").value(org.hamcrest.Matchers.contains("NORMAL")));
    }
}
