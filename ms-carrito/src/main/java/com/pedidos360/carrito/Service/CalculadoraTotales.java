package com.pedidos360.carrito.Service;

import com.pedidos360.carrito.Model.Cupon;
import com.pedidos360.carrito.Model.MetodoEnvio;

import java.util.List;
import java.util.Optional;

/**
 * Totales del carrito (clase pura). Es la ÚNICA fórmula: la usan el GET del carrito y la creación
 * del pedido, así lo que el cliente ve es exactamente lo que paga.
 */
public final class CalculadoraTotales {

    public static final long COSTO_ENVIO = 3_990L;
    public static final long UMBRAL_ENVIO_GRATIS = 100_000L;

    private CalculadoraTotales() {
    }

    public record Linea(long precioUnitario, int cantidad) {
    }

    public record Totales(long subtotal, long descuento, long costoEnvio, long total,
                          boolean cuponAplicado, String avisoCupon, long faltaParaEnvioGratis) {
    }

    /** RETIRO es gratis; sin método elegido todavía no hay costo que mostrar. */
    public static long costoEnvio(MetodoEnvio metodo, long subtotal) {
        if (metodo != MetodoEnvio.ENVIO) {
            return 0L;
        }
        return subtotal >= UMBRAL_ENVIO_GRATIS ? 0L : COSTO_ENVIO;
    }

    /** Vacío si el cupón aplica; si no, el motivo legible (se muestra tal cual en la tienda). */
    public static Optional<String> motivoRechazoCupon(Cupon cupon, long subtotal) {
        if (!cupon.isActivo()) {
            return Optional.of("El cupón " + cupon.getCodigo() + " no está vigente");
        }
        if (subtotal < cupon.getMinimoCompra()) {
            return Optional.of("El cupón " + cupon.getCodigo() + " requiere una compra mínima de "
                    + Formato.clp(cupon.getMinimoCompra()) + " (subtotal actual " + Formato.clp(subtotal) + ")");
        }
        return Optional.empty();
    }

    public static Totales calcular(List<Linea> lineas, Cupon cupon, MetodoEnvio metodo) {
        long subtotal = lineas.stream().mapToLong(l -> l.precioUnitario() * l.cantidad()).sum();
        long envio = costoEnvio(metodo, subtotal);
        long descuento = 0L;
        boolean aplicado = false;
        String aviso = null;

        if (cupon != null) {
            Optional<String> rechazo = motivoRechazoCupon(cupon, subtotal);
            if (rechazo.isPresent()) {
                // El cupón queda guardado pero no descuenta: si el cliente vuelve a superar el
                // mínimo, se reactiva solo, sin pedirle que lo ingrese de nuevo.
                aviso = rechazo.get();
            } else {
                aplicado = true;
                switch (cupon.getTipo()) {
                    case PORCENTAJE -> descuento = subtotal * Math.min(cupon.getValor(), 100L) / 100L;
                    case MONTO -> descuento = Math.min(cupon.getValor(), subtotal);
                    case ENVIO_GRATIS -> {
                        descuento = envio;
                        if (metodo == null) {
                            aviso = "Se aplicará al elegir despacho a domicilio";
                        } else if (metodo == MetodoEnvio.RETIRO) {
                            aviso = "El retiro en sucursal ya es gratis";
                        } else if (envio == 0L) {
                            aviso = "Tu compra ya tiene envío gratis";
                        }
                    }
                }
            }
        }

        long total = subtotal - descuento + envio;
        long falta = Math.max(0L, UMBRAL_ENVIO_GRATIS - subtotal);
        return new Totales(subtotal, descuento, envio, total, aplicado, aviso, falta);
    }
}
