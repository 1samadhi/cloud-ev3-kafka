package com.pedidos360.productos.Exception;

import java.util.List;

import com.pedidos360.productos.DTO.FaltanteDTO;

/** Al menos un ítem no alcanzó: la reserva completa se rechaza (todo o nada) → 409. */
public class StockInsuficienteException extends RuntimeException {

    private final transient List<FaltanteDTO> faltantes;

    public StockInsuficienteException(List<FaltanteDTO> faltantes) {
        super("Stock insuficiente para " + faltantes.size() + " producto(s)");
        this.faltantes = List.copyOf(faltantes);
    }

    public List<FaltanteDTO> getFaltantes() {
        return faltantes;
    }
}
