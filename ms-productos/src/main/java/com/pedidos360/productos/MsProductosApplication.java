package com.pedidos360.productos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ms-productos: dueño del catálogo y del stock de Pedidos360.
 *
 * Es la fuente de verdad del catálogo: ms-carrito le pide precios y reservas por REST
 * (comandos transaccionales) y ms-recomendaciones reconstruye el catálogo leyendo el
 * tópico compactado que este servicio alimenta (estado, no historia).
 */
@SpringBootApplication
public class MsProductosApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsProductosApplication.class, args);
    }
}
