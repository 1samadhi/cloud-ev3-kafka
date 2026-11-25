package com.pedidos360.productos.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Línea de una reserva: cuántas unidades de un producto se descontaron para el pedido. */
@Entity
@Table(name = "reserva_stock_item")
@Getter
@NoArgsConstructor
public class ItemReservaStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_pedido", nullable = false)
    private ReservaStock reserva;

    @Column(nullable = false, length = 64)
    private String productoId;

    @Column(nullable = false)
    private Integer cantidad;

    ItemReservaStock(ReservaStock reserva, String productoId, Integer cantidad) {
        this.reserva = reserva;
        this.productoId = productoId;
        this.cantidad = cantidad;
    }
}
