package com.pedidos360.carrito.Service;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Formato de montos para los mensajes al cliente ("$649.990"). */
public final class Formato {

    private Formato() {
    }

    public static String clp(long monto) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setGroupingSeparator('.');
        // DecimalFormat no es thread-safe: se crea uno por llamada (barato y sin estado compartido).
        return "$" + new DecimalFormat("#,##0", simbolos).format(monto);
    }
}
