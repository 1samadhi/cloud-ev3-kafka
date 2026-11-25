package com.pedidos360.carrito.Config;

import com.pedidos360.carrito.Model.Cupon;
import com.pedidos360.carrito.Model.TipoCupon;
import com.pedidos360.carrito.Repository.CuponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Cupones de §8. Se inserta cada código sólo si no existe (no "si la tabla está vacía"): así
 * un cupón desactivado a mano en la BD no se reactiva al reiniciar, y uno nuevo de la semilla
 * aparece aunque ya existieran otros.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemillaCupones implements CommandLineRunner {

    private final CuponRepository cuponRepository;

    public static List<Cupon> cuponesSemilla() {
        return List.of(
                Cupon.builder().codigo("BIENVENIDA10").tipo(TipoCupon.PORCENTAJE).valor(10).minimoCompra(0)
                        .activo(true).descripcion("10 % de descuento en tu compra").build(),
                Cupon.builder().codigo("ENVIOGRATIS").tipo(TipoCupon.ENVIO_GRATIS).valor(0).minimoCompra(0)
                        .activo(true).descripcion("Despacho a domicilio sin costo").build(),
                Cupon.builder().codigo("GAMER5000").tipo(TipoCupon.MONTO).valor(5000).minimoCompra(50000)
                        .activo(true).descripcion("$5.000 de descuento en compras desde $50.000").build());
    }

    @Override
    public void run(String... args) {
        for (Cupon cupon : cuponesSemilla()) {
            if (!cuponRepository.existsById(cupon.getCodigo())) {
                cuponRepository.save(cupon);
                log.info("Cupón semilla creado: {}", cupon.getCodigo());
            }
        }
    }
}
