package com.pedidos360.carrito.Service;

import com.pedidos360.carrito.Model.Cupon;
import com.pedidos360.carrito.Model.MetodoEnvio;
import com.pedidos360.carrito.Model.TipoCupon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Totales, cupones y envío de §8 (JUnit puro, sin Spring). */
class CalculadoraTotalesTest {

    private static final Cupon BIENVENIDA10 = Cupon.builder().codigo("BIENVENIDA10").tipo(TipoCupon.PORCENTAJE)
            .valor(10).minimoCompra(0).activo(true).build();
    private static final Cupon ENVIOGRATIS = Cupon.builder().codigo("ENVIOGRATIS").tipo(TipoCupon.ENVIO_GRATIS)
            .valor(0).minimoCompra(0).activo(true).build();
    private static final Cupon GAMER5000 = Cupon.builder().codigo("GAMER5000").tipo(TipoCupon.MONTO)
            .valor(5000).minimoCompra(50000).activo(true).build();

    private static List<CalculadoraTotales.Linea> lineas(long precio, int cantidad) {
        return List.of(new CalculadoraTotales.Linea(precio, cantidad));
    }

    @Test
    void envioCuesta3990BajoElUmbral() {
        var t = CalculadoraTotales.calcular(lineas(99_999, 1), null, MetodoEnvio.ENVIO);
        assertThat(t.subtotal()).isEqualTo(99_999);
        assertThat(t.costoEnvio()).isEqualTo(3_990);
        assertThat(t.total()).isEqualTo(103_989);
        assertThat(t.faltaParaEnvioGratis()).isEqualTo(1);
    }

    @Test
    void envioGratisDesdeExactamente100000() {
        var t = CalculadoraTotales.calcular(lineas(50_000, 2), null, MetodoEnvio.ENVIO);
        assertThat(t.subtotal()).isEqualTo(100_000);
        assertThat(t.costoEnvio()).isZero();
        assertThat(t.total()).isEqualTo(100_000);
        assertThat(t.faltaParaEnvioGratis()).isZero();
    }

    @Test
    void retiroEnSucursalEsGratisYSinMetodoNoHayCosto() {
        assertThat(CalculadoraTotales.calcular(lineas(10_000, 1), null, MetodoEnvio.RETIRO).costoEnvio()).isZero();
        assertThat(CalculadoraTotales.calcular(lineas(10_000, 1), null, null).costoEnvio()).isZero();
    }

    @Test
    void subtotalSumaPrecioPorCantidadDeCadaLinea() {
        var t = CalculadoraTotales.calcular(List.of(
                new CalculadoraTotales.Linea(649_990, 1),
                new CalculadoraTotales.Linea(74_990, 2)), null, MetodoEnvio.ENVIO);
        assertThat(t.subtotal()).isEqualTo(799_970);
        assertThat(t.total()).isEqualTo(799_970);
    }

    @Test
    void bienvenida10DescuentaElDiezPorCiento() {
        var t = CalculadoraTotales.calcular(lineas(50_000, 1), BIENVENIDA10, MetodoEnvio.ENVIO);
        assertThat(t.cuponAplicado()).isTrue();
        assertThat(t.descuento()).isEqualTo(5_000);
        // El umbral de envío gratis se mide sobre el subtotal, antes del descuento.
        assertThat(t.costoEnvio()).isEqualTo(3_990);
        assertThat(t.total()).isEqualTo(50_000 - 5_000 + 3_990);
    }

    @Test
    void gamer5000NoAplicaBajoElMinimoYDejaAviso() {
        var t = CalculadoraTotales.calcular(lineas(49_999, 1), GAMER5000, MetodoEnvio.RETIRO);
        assertThat(t.cuponAplicado()).isFalse();
        assertThat(t.descuento()).isZero();
        assertThat(t.avisoCupon()).contains("GAMER5000").contains("$50.000");
        assertThat(t.total()).isEqualTo(49_999);
    }

    @Test
    void gamer5000AplicaDesdeElMinimo() {
        var t = CalculadoraTotales.calcular(lineas(50_000, 1), GAMER5000, MetodoEnvio.RETIRO);
        assertThat(t.cuponAplicado()).isTrue();
        assertThat(t.descuento()).isEqualTo(5_000);
        assertThat(t.total()).isEqualTo(45_000);
    }

    @Test
    void cuponDeMontoNuncaSuperaElSubtotal() {
        Cupon grande = Cupon.builder().codigo("X").tipo(TipoCupon.MONTO).valor(80_000).minimoCompra(0).activo(true).build();
        var t = CalculadoraTotales.calcular(lineas(30_000, 1), grande, MetodoEnvio.RETIRO);
        assertThat(t.descuento()).isEqualTo(30_000);
        assertThat(t.total()).isZero();
    }

    @Test
    void envioGratisDescuentaElDespacho() {
        var t = CalculadoraTotales.calcular(lineas(30_000, 1), ENVIOGRATIS, MetodoEnvio.ENVIO);
        assertThat(t.costoEnvio()).isEqualTo(3_990);
        assertThat(t.descuento()).isEqualTo(3_990);
        assertThat(t.total()).isEqualTo(30_000);
        assertThat(t.avisoCupon()).isNull();
    }

    @Test
    void envioGratisConRetiroNoDescuentaYAvisa() {
        var t = CalculadoraTotales.calcular(lineas(30_000, 1), ENVIOGRATIS, MetodoEnvio.RETIRO);
        assertThat(t.descuento()).isZero();
        assertThat(t.total()).isEqualTo(30_000);
        assertThat(t.avisoCupon()).contains("retiro");
    }

    @Test
    void cuponInactivoNoDescuenta() {
        Cupon inactivo = Cupon.builder().codigo("VIEJO").tipo(TipoCupon.PORCENTAJE).valor(50).minimoCompra(0).activo(false).build();
        var t = CalculadoraTotales.calcular(lineas(10_000, 1), inactivo, MetodoEnvio.RETIRO);
        assertThat(t.cuponAplicado()).isFalse();
        assertThat(t.descuento()).isZero();
        assertThat(t.avisoCupon()).contains("no está vigente");
    }

    @Test
    void carritoVacioTotalCero() {
        var t = CalculadoraTotales.calcular(List.of(), null, MetodoEnvio.ENVIO);
        assertThat(t.subtotal()).isZero();
        assertThat(t.costoEnvio()).isEqualTo(3_990);
    }
}
