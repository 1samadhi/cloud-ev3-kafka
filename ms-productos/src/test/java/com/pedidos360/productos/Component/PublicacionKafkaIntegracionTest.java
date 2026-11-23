package com.pedidos360.productos.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.productos.ConsumidorPrueba;
import com.pedidos360.productos.PruebaIntegracionBase;
import com.pedidos360.productos.Config.SemillaCatalogo;

/**
 * Publicación real contra Kafka embebido: VER_PRODUCTO en interacciones-user y snapshots
 * en catalogo-productos (§7.2).
 */
class PublicacionKafkaIntegracionTest extends PruebaIntegracionBase {

    private static final Duration ESPERA = Duration.ofSeconds(20);
    private static final String INTERACCIONES = "interacciones-user";
    private static final String CATALOGO = "catalogo-productos";

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode valor(ConsumerRecord<String, String> r) {
        try {
            return json.readTree(r.value());
        } catch (Exception e) {
            throw new AssertionError("El valor no es JSON válido: " + r.value(), e);
        }
    }

    @Test
    void verDetalleConUsuarioPublicaVerProductoConKeyUsuario() throws Exception {
        String usuario = "u-" + UUID.randomUUID().toString().substring(0, 8);
        try (ConsumidorPrueba c = ConsumidorPrueba.desdeAhora(broker.getBrokersAsString(), INTERACCIONES)) {
            mvc.perform(get("/api/productos/item_101").header("X-Usuario-Id", usuario))
                    .andExpect(status().isOk());

            ConsumerRecord<String, String> r = c.esperar(rec -> usuario.equals(rec.key()), ESPERA);
            JsonNode v = valor(r);
            assertThat(r.key()).isEqualTo(usuario);
            assertThat(v.get("usuarioId").asText()).isEqualTo(usuario);
            assertThat(v.get("tipoEvento").asText()).isEqualTo("VER_PRODUCTO");
            assertThat(v.get("productoId").asText()).isEqualTo("item_101");
            assertThat(v.get("categoria").asText()).isEqualTo("celulares");
            assertThat(v.get("subcategoria").asText()).isEqualTo("gama-alta");
            assertThat(v.get("marca").asText()).isEqualTo("Samsung");
            assertThat(v.get("origen").asText()).isEqualTo("ms-productos");
            assertThat(v.get("idEvento").asText()).isNotBlank();
            assertThat(v.get("timestamp").asLong()).isPositive();
            // El campo de validación interno (@AssertTrue) no debe filtrarse al JSON del contrato.
            assertThat(v.has("productoIdValidoParaTipo")).isFalse();
            // ADD_TYPE_INFO_HEADERS=false: el consumidor no depende del paquete Java del productor.
            assertThat(r.headers().lastHeader("__TypeId__")).isNull();
        }
    }

    @Test
    void verDetalleSinUsuarioNoPublica() throws Exception {
        String centinela = "centinela-" + UUID.randomUUID().toString().substring(0, 8);
        try (ConsumidorPrueba c = ConsumidorPrueba.desdeAhora(broker.getBrokersAsString(), INTERACCIONES)) {
            mvc.perform(get("/api/productos/item_103")).andExpect(status().isOk());
            // El centinela marca "ya pasó suficiente": si item_103 se hubiera publicado, estaría antes.
            mvc.perform(get("/api/productos/item_104").header("X-Usuario-Id", centinela)).andExpect(status().isOk());
            c.esperar(rec -> centinela.equals(rec.key()), ESPERA);
            List<ConsumerRecord<String, String>> leidos = c.leerDurante(Duration.ofMillis(800));

            assertThat(leidos).noneMatch(rec -> "item_103".equals(valor(rec).path("productoId").asText()));
        }
    }

    @Test
    void reservaReposicionYLiberacionPublicanSnapshotConElStockNuevo() throws Exception {
        String idPedido = "P-kafka-" + UUID.randomUUID().toString().substring(0, 8);
        String cuerpo = "{\"idPedido\":\"" + idPedido + "\",\"items\":[{\"productoId\":\"item_110\",\"cantidad\":2}]}";
        try (ConsumidorPrueba c = ConsumidorPrueba.desdeAhora(broker.getBrokersAsString(), CATALOGO)) {
            // Reserva: 5 → 3
            mvc.perform(post("/api/productos/stock/reservas").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isOk());
            ConsumerRecord<String, String> r = c.esperar(rec -> "item_110".equals(rec.key()) && valor(rec).get("stock").asInt() == 3, ESPERA);
            JsonNode v = valor(r);
            assertThat(v.get("id").asText()).isEqualTo("item_110");
            assertThat(v.get("nombre").asText()).isEqualTo("PlayStation 5");
            assertThat(v.get("precio").asLong()).isEqualTo(549990L);
            assertThat(v.get("actualizadoEn").asLong()).isPositive();
            assertThat(r.headers().lastHeader("__TypeId__")).isNull();

            // Reposición: 3 + 4 = 7
            mvc.perform(put("/api/productos/item_110/stock").contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":4}"))
                    .andExpect(status().isOk());
            c.esperar(rec -> "item_110".equals(rec.key()) && valor(rec).get("stock").asInt() == 7, ESPERA);

            // Liberación: 7 + 2 = 9
            mvc.perform(post("/api/productos/stock/liberaciones").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isOk());
            c.esperar(rec -> "item_110".equals(rec.key()) && valor(rec).get("stock").asInt() == 9, ESPERA);
        }
    }

    @Test
    void reservaRechazadaNoPublicaSnapshot() throws Exception {
        String cuerpo = "{\"idPedido\":\"P-rechazo-kafka\",\"items\":[{\"productoId\":\"item_109\",\"cantidad\":1},"
                + "{\"productoId\":\"item_110\",\"cantidad\":99}]}";
        try (ConsumidorPrueba c = ConsumidorPrueba.desdeAhora(broker.getBrokersAsString(), CATALOGO)) {
            mvc.perform(post("/api/productos/stock/reservas").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isConflict());
            // Centinela: un cambio real posterior. Cuando llega, cualquier snapshot del rechazo ya habría llegado.
            mvc.perform(put("/api/productos/item_117/stock").contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":1}"))
                    .andExpect(status().isOk());
            c.esperar(rec -> "item_117".equals(rec.key()), ESPERA);
            List<ConsumerRecord<String, String>> leidos = c.leerDurante(Duration.ofMillis(800));

            assertThat(leidos).noneMatch(rec -> "item_109".equals(rec.key()) || "item_110".equals(rec.key()));
        }
    }

    @Test
    void alArrancarSePublicaElCatalogoCompletoConKeyProductoId() {
        Set<String> esperados = SemillaCatalogo.productosSemilla(java.time.Instant.now()).stream()
                .map(p -> p.getId()).collect(Collectors.toSet());
        try (ConsumidorPrueba c = ConsumidorPrueba.desdeInicio(broker.getBrokersAsString(), CATALOGO)) {
            Set<String> vistos = new HashSet<>();
            c.esperar(rec -> {
                // La key debe ser el id del snapshot: es la identidad para la compactación.
                if (rec.key() != null && rec.key().equals(valor(rec).get("id").asText())) {
                    vistos.add(rec.key());
                }
                return vistos.containsAll(esperados);
            }, ESPERA);
            assertThat(vistos).containsAll(esperados);
        }
    }
}
