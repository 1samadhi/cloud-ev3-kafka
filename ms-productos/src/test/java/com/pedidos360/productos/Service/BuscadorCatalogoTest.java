package com.pedidos360.productos.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pedidos360.productos.Config.SemillaCatalogo;
import com.pedidos360.productos.Exception.PeticionInvalidaException;
import com.pedidos360.productos.Model.Producto;

/** Búsqueda y orden del catálogo con JUnit puro (sin Spring). */
class BuscadorCatalogoTest {

    private final List<Producto> catalogo = SemillaCatalogo.productosSemilla(Instant.now());

    private List<String> ids(String q, OrdenCatalogo orden) {
        return BuscadorCatalogo.filtrarYOrdenar(catalogo, q, orden).stream().map(Producto::getId).toList();
    }

    @Test
    void sinTerminoOrdenaPorCalificacionYLuegoId() {
        List<String> r = ids(null, OrdenCatalogo.RELEVANCIA);
        assertThat(r).hasSize(20);
        // 4.9: MacBook (105) y PS5 (110), desempatados por id.
        assertThat(r.subList(0, 2)).containsExactly("item_105", "item_110");
    }

    @Test
    void ignoraTildesYMayusculas() {
        assertThat(ids("CÁMARAS", OrdenCatalogo.RELEVANCIA)).containsExactly("item_119", "item_120");
        assertThat(ids("cancelacion", OrdenCatalogo.RELEVANCIA)).containsExactly("item_107");
    }

    @Test
    void todosLosTerminosDebenCoincidir() {
        assertThat(ids("sony audifonos", OrdenCatalogo.RELEVANCIA)).containsExactly("item_107");
    }

    @Test
    void coincidenciaEnNombrePesaMasQueEnDescripcion() {
        // "iphone" está en el nombre de 102 y 118; empatan en puntaje y gana la mejor calificación.
        assertThat(ids("iphone", OrdenCatalogo.RELEVANCIA)).containsExactly("item_102", "item_118");
        // "alexa": Fire TV (descripción) y Echo Dot (descripción) empatan → calificación 4.5 > 4.4.
        assertThat(ids("alexa", OrdenCatalogo.RELEVANCIA)).containsExactly("item_115", "item_114");
        // "oled": nombre de Switch OLED (10+1) gana a Galaxy (AMOLED) y Moto G84 (pOLED), que sólo
        // coinciden en la descripción (1) y se desempatan por calificación.
        assertThat(ids("oled", OrdenCatalogo.RELEVANCIA)).containsExactly("item_111", "item_101", "item_103");
    }

    @Test
    void ordenPorPrecio() {
        assertThat(ids("apple", OrdenCatalogo.PRECIO_ASC)).containsExactly("item_118", "item_108", "item_102", "item_105");
        assertThat(ids("apple", OrdenCatalogo.PRECIO_DESC)).containsExactly("item_105", "item_102", "item_108", "item_118");
    }

    @Test
    void parseoDelOrden() {
        assertThat(OrdenCatalogo.desde(null)).isEqualTo(OrdenCatalogo.RELEVANCIA);
        assertThat(OrdenCatalogo.desde(" ")).isEqualTo(OrdenCatalogo.RELEVANCIA);
        assertThat(OrdenCatalogo.desde("PRECIO_DESC")).isEqualTo(OrdenCatalogo.PRECIO_DESC);
        assertThatThrownBy(() -> OrdenCatalogo.desde("barato")).isInstanceOf(PeticionInvalidaException.class);
    }
}
