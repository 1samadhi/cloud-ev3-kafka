package com.pedidos360.carrito.Exception;

import com.pedidos360.carrito.DTO.FaltanteDTO;

import java.util.List;

/** 409 con la lista de faltantes que devolvió ms-productos (se propaga tal cual a la tienda). */
public class StockInsuficienteException extends RuntimeException {

    private final List<FaltanteDTO> faltantes;

    public StockInsuficienteException(String mensaje, List<FaltanteDTO> faltantes) {
        super(mensaje);
        this.faltantes = faltantes == null ? List.of() : List.copyOf(faltantes);
    }

    public List<FaltanteDTO> getFaltantes() {
        return faltantes;
    }
}
