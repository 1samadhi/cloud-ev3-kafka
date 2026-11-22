package com.pedidos360.productos.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.pedidos360.productos.PruebaIntegracionBase;
import com.pedidos360.productos.DTO.ItemReservaDTO;
import com.pedidos360.productos.DTO.PeticionReservaDTO;
import com.pedidos360.productos.DTO.RespuestaReservaDTO;
import com.pedidos360.productos.Exception.ConflictoReservaException;
import com.pedidos360.productos.Exception.ProductoNoEncontradoException;
import com.pedidos360.productos.Exception.StockInsuficienteException;
import com.pedidos360.productos.Model.EstadoReserva;

/** Reglas de reservas y liberaciones contra la base real (H2) y con hilos concurrentes. */
class ServicioStockIntegracionTest extends PruebaIntegracionBase {

    @Autowired
    private ServicioStock servicio;

    private static PeticionReservaDTO peticion(String idPedido, Object... pares) {
        List<ItemReservaDTO> items = new ArrayList<>();
        for (int i = 0; i < pares.length; i += 2) {
            items.add(new ItemReservaDTO((String) pares[i], (Integer) pares[i + 1]));
        }
        return new PeticionReservaDTO(idPedido, items);
    }

    @Test
    void reservaDescuentaTodosLosItemsYRegistraLaReserva() {
        RespuestaReservaDTO r = servicio.reservar(peticion("P-1", "item_110", 2, "item_112", 5));

        assertThat(r.resultado()).isEqualTo(ServicioStock.RESERVADA);
        assertThat(stock("item_110")).isEqualTo(3);
        assertThat(stock("item_112")).isEqualTo(65);
        assertThat(reservas.findById("P-1")).get().extracting(x -> x.getEstado()).isEqualTo(EstadoReserva.RESERVADA);
    }

    @Test
    void reservaConUnFaltanteNoDescuentaNingunoYResponde409ConFaltantes() throws Exception {
        String cuerpo = "{\"idPedido\":\"P-todo-o-nada\",\"items\":["
                + "{\"productoId\":\"item_109\",\"cantidad\":2},"
                + "{\"productoId\":\"item_110\",\"cantidad\":6},"
                + "{\"productoId\":\"item_112\",\"cantidad\":1}]}";

        mvc.perform(post("/api/productos/stock/reservas").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Stock insuficiente"))
                .andExpect(jsonPath("$.faltantes.length()").value(1))
                .andExpect(jsonPath("$.faltantes[0].productoId").value("item_110"))
                .andExpect(jsonPath("$.faltantes[0].disponible").value(5))
                .andExpect(jsonPath("$.timestamp").isNumber());

        // TODO o NADA: los ítems que sí alcanzaban tampoco se tocaron.
        assertThat(stock("item_109")).isEqualTo(52);
        assertThat(stock("item_110")).isEqualTo(5);
        assertThat(stock("item_112")).isEqualTo(70);
        assertThat(reservas.findById("P-todo-o-nada")).isEmpty();
    }

    @Test
    void productoInexistenteSeInformaComoFaltanteConDisponibleCero() {
        assertThatThrownBy(() -> servicio.reservar(peticion("P-2", "item_101", 1, "item_999", 1)))
                .isInstanceOfSatisfying(StockInsuficienteException.class, e ->
                        assertThat(e.getFaltantes()).extracting(f -> f.productoId() + ":" + f.disponible())
                                .containsExactly("item_999:0"));
        assertThat(stock("item_101")).isEqualTo(14);
    }

    @Test
    void lineasRepetidasDelMismoProductoSeSumanAntesDeValidar() {
        // 3 + 3 = 6 > 5: cada línea por separado alcanzaría, la suma no.
        assertThatThrownBy(() -> servicio.reservar(peticion("P-3", "item_110", 3, "item_110", 3)))
                .isInstanceOf(StockInsuficienteException.class);
        assertThat(stock("item_110")).isEqualTo(5);
    }

    @Test
    void reservaRepetidaDelMismoPedidoNoDescuentaDosVeces() {
        servicio.reservar(peticion("P-4", "item_102", 2));
        RespuestaReservaDTO segunda = servicio.reservar(peticion("P-4", "item_102", 2));

        assertThat(segunda.resultado()).isEqualTo(ServicioStock.YA_RESERVADA);
        assertThat(stock("item_102")).isEqualTo(4);
    }

    @Test
    void liberacionDevuelveLoReservadoYEsIdempotente() {
        servicio.reservar(peticion("P-5", "item_105", 3, "item_119", 2));
        assertThat(stock("item_105")).isEqualTo(6);

        RespuestaReservaDTO primera = servicio.liberar(peticion("P-5", "item_105", 3, "item_119", 2));
        RespuestaReservaDTO segunda = servicio.liberar(peticion("P-5", "item_105", 3, "item_119", 2));

        assertThat(primera.resultado()).isEqualTo(ServicioStock.LIBERADA);
        assertThat(segunda.resultado()).isEqualTo(ServicioStock.YA_LIBERADA);
        assertThat(stock("item_105")).isEqualTo(9);
        assertThat(stock("item_119")).isEqualTo(7);
        assertThat(reservas.findById("P-5")).get().extracting(x -> x.getEstado()).isEqualTo(EstadoReserva.LIBERADA);
    }

    @Test
    void liberacionDevuelveLoReservadoAunqueElCuerpoDigaOtraCosa() {
        servicio.reservar(peticion("P-6", "item_111", 2));
        servicio.liberar(peticion("P-6", "item_111", 50));
        assertThat(stock("item_111")).isEqualTo(27);
    }

    @Test
    void liberacionSinReservaNoInventaStock() {
        RespuestaReservaDTO r = servicio.liberar(peticion("P-fantasma", "item_101", 100));
        assertThat(r.resultado()).isEqualTo(ServicioStock.SIN_RESERVA);
        assertThat(stock("item_101")).isEqualTo(14);
    }

    @Test
    void unPedidoLiberadoNoPuedeVolverAReservar() {
        servicio.reservar(peticion("P-7", "item_103", 1));
        servicio.liberar(peticion("P-7", "item_103", 1));
        assertThatThrownBy(() -> servicio.reservar(peticion("P-7", "item_103", 1)))
                .isInstanceOf(ConflictoReservaException.class);
        assertThat(stock("item_103")).isEqualTo(40);
    }

    @Test
    void reponerSumaAlStockYFallaConProductoInexistente() {
        assertThat(servicio.reponer("item_110", 10).getStock()).isEqualTo(15);
        assertThatThrownBy(() -> servicio.reponer("item_999", 1)).isInstanceOf(ProductoNoEncontradoException.class);
    }

    /** Ejecuta las tareas a la vez (una barrera las suelta juntas) y devuelve sus resultados. */
    private static <T> List<Object> enParalelo(List<Callable<T>> tareas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tareas.size());
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> t : tareas) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return t.call();
                }));
            }
            largada.countDown();
            List<Object> resultados = new ArrayList<>();
            for (Future<T> f : futuros) {
                try {
                    resultados.add(f.get(60, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException e) {
                    resultados.add(e.getCause());
                }
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reservasConcurrentesNoSobrevenden() throws Exception {
        // item_110 tiene 5 unidades y 12 pedidos distintos quieren 1 cada uno, al mismo tiempo.
        List<Callable<RespuestaReservaDTO>> tareas = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String id = "P-conc-" + i;
            tareas.add(() -> servicio.reservar(peticion(id, "item_110", 1)));
        }
        List<Object> resultados = enParalelo(tareas);

        long exitos = resultados.stream().filter(RespuestaReservaDTO.class::isInstance).count();
        long sinStock = resultados.stream().filter(StockInsuficienteException.class::isInstance).count();
        assertThat(exitos).as("resultados: %s", resultados).isEqualTo(5);
        assertThat(sinStock).isEqualTo(7);
        assertThat(stock("item_110")).isZero();
        assertThat(reservas.count()).isEqualTo(5);
    }

    @Test
    void reservasConcurrentesMultiProductoSonTodoONada() throws Exception {
        // Cada pedido quiere 1 PS5 (stock 5) + 1 iPhone (stock 6). Sólo 5 pueden completarse;
        // los otros no deben llevarse el iPhone "a medias".
        List<Callable<RespuestaReservaDTO>> tareas = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String id = "P-multi-" + i;
            // Orden de ítems invertido en la mitad de los pedidos: sin bloqueo ordenado, esto haría deadlock.
            tareas.add(i % 2 == 0
                    ? () -> servicio.reservar(peticion(id, "item_110", 1, "item_102", 1))
                    : () -> servicio.reservar(peticion(id, "item_102", 1, "item_110", 1)));
        }
        List<Object> resultados = enParalelo(tareas);

        long exitos = resultados.stream().filter(RespuestaReservaDTO.class::isInstance).count();
        assertThat(exitos).as("resultados: %s", resultados).isEqualTo(5);
        assertThat(stock("item_110")).isZero();
        assertThat(stock("item_102")).isEqualTo(1);
    }

    @Test
    void liberacionesConcurrentesDelMismoPedidoDevuelvenElStockUnaSolaVez() throws Exception {
        servicio.reservar(peticion("P-lib", "item_112", 10));
        assertThat(stock("item_112")).isEqualTo(60);

        List<Callable<RespuestaReservaDTO>> tareas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tareas.add(() -> servicio.liberar(peticion("P-lib", "item_112", 10)));
        }
        List<Object> resultados = enParalelo(tareas);

        assertThat(resultados).allMatch(RespuestaReservaDTO.class::isInstance);
        assertThat(resultados).filteredOn(r -> ServicioStock.LIBERADA.equals(((RespuestaReservaDTO) r).resultado())).hasSize(1);
        assertThat(stock("item_112")).isEqualTo(70);
    }
}
