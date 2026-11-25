package com.pedidos360.productos.DTO;

/** Semáforo del panel admin (§7.2): AGOTADO = 0, BAJO = 1..umbral, NORMAL = sobre el umbral. */
public enum NivelStock {
    AGOTADO,
    BAJO,
    NORMAL;

    public static NivelStock clasificar(int stock, int umbralBajo) {
        if (stock <= 0) {
            return AGOTADO;
        }
        return stock <= umbralBajo ? BAJO : NORMAL;
    }
}
