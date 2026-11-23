package com.pedidos360.productos.Exception;

public class ProductoNoEncontradoException extends RuntimeException {

    private final String productoId;

    public ProductoNoEncontradoException(String productoId) {
        super("No existe el producto " + productoId);
        this.productoId = productoId;
    }

    public String getProductoId() {
        return productoId;
    }
}
