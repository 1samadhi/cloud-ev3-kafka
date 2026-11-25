package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.Config.CatalogoSemilla;
import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.InteractionRequestsDTO;
import com.kafka.recomendaciones.DTO.TipoEvento;
import com.kafka.recomendaciones.Exception.PayloadInvalidoException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Traduccion del cuerpo viejo de la consola al EventoInteraccionDTO del contrato. */
class TraductorInteraccionesTest {

    private static final long AHORA = 1_800_000_000_000L;

    private final TraductorInteracciones traductor = new TraductorInteracciones(
            new CatalogoEnMemoria(new CatalogoSemilla()), Clock.fixed(Instant.ofEpochMilli(AHORA), ZoneOffset.UTC));
    private final Validator validador = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @CsvSource({"view,VER_PRODUCTO", "cart,AGREGAR_CARRITO", "purchase,COMPRA", "VIEW,VER_PRODUCTO",
            "COMPRA,COMPRA", "agregar_carrito,AGREGAR_CARRITO"})
    void traduceLaAccion(String accion, TipoEvento esperado) {
        assertThat(TraductorInteracciones.tipoDesdeAccion(accion)).isEqualTo(esperado);
    }

    @Test
    void productoConocidoTomaLosAtributosDelCatalogo() {
        EventoInteraccionDTO e = traductor.traducir(
                new InteractionRequestsDTO("  cliente-1 ", "item_101", "view", "electronica", "celular"));

        assertThat(e.getUsuarioId()).isEqualTo("cliente-1");
        assertThat(e.getTipoEvento()).isEqualTo(TipoEvento.VER_PRODUCTO);
        assertThat(e.getProductoId()).isEqualTo("item_101");
        // La categoria vieja de la consola (electronica) se reemplaza por el slug de §7.1.
        assertThat(e.getCategoria()).isEqualTo("celulares");
        assertThat(e.getSubcategoria()).isEqualTo("gama-alta");
        assertThat(e.getMarca()).isEqualTo("Samsung");
        assertThat(e.getOrigen()).isEqualTo("ms-recomendaciones");
        assertThat(e.getTimestamp()).isEqualTo(AHORA);
        assertThat(e.getIdEvento()).hasSize(36);
        assertThat(e.getDetalle()).containsEntry("accionOriginal", "view");
        assertThat(validador.validate(e)).isEmpty();
    }

    @Test
    void productoDesconocidoConservaLoQueVino() {
        EventoInteraccionDTO e = traductor.traducir(
                new InteractionRequestsDTO("c", "item_999", "cart", "hogar", "lampara"));

        assertThat(e.getCategoria()).isEqualTo("hogar");
        assertThat(e.getSubcategoria()).isEqualTo("lampara");
        assertThat(e.getMarca()).isNull();
        assertThat(validador.validate(e)).isEmpty();
    }

    @Test
    void accionDesconocidaOSinProductoSeRechaza() {
        assertThatThrownBy(() -> TraductorInteracciones.tipoDesdeAccion("volar"))
                .isInstanceOf(PayloadInvalidoException.class).hasMessageContaining("volar");
        assertThatThrownBy(() -> TraductorInteracciones.tipoDesdeAccion(" "))
                .isInstanceOf(PayloadInvalidoException.class);
        // Cupon y envio no tienen producto: no entran por este endpoint.
        assertThatThrownBy(() -> TraductorInteracciones.tipoDesdeAccion("APLICAR_CUPON"))
                .isInstanceOf(PayloadInvalidoException.class);
    }
}
