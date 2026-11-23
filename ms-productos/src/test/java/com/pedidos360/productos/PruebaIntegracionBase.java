package com.pedidos360.productos;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;

import com.pedidos360.productos.Config.SemillaCatalogo;
import com.pedidos360.productos.Repository.ProductoRepositorio;
import com.pedidos360.productos.Repository.ReservaStockRepositorio;

/**
 * Base de las pruebas de integración: contexto completo + H2 + Kafka embebido (KRaft).
 *
 * Todas las subclases comparten la misma configuración, así Spring cachea UN solo contexto
 * y UN solo broker para toda la suite. Antes de cada prueba se reinicia el catálogo a la
 * semilla de §7.1 para que el stock de una prueba no contamine a la siguiente (el tópico
 * no se limpia: las pruebas de Kafka leen "desde ahora" o filtran por key).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(kraft = true, partitions = 3,
        topics = {"interacciones-user", "catalogo-productos"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public abstract class PruebaIntegracionBase {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ProductoRepositorio productos;

    @Autowired
    protected ReservaStockRepositorio reservas;

    @Autowired
    protected SemillaCatalogo semilla;

    @Autowired
    protected EmbeddedKafkaBroker broker;

    @BeforeEach
    void reiniciarCatalogo() {
        reservas.deleteAll();
        productos.deleteAll();
        semilla.cargarSiVacio();
    }

    protected int stock(String productoId) {
        return productos.findById(productoId).orElseThrow().getStock();
    }
}
