package com.pedidos360.carrito.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Copia inmutable de la línea al momento de comprar (el precio no cambia si cambia el catálogo). */
@Entity
@Table(name = "item_pedido")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemPedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pedido_id")
    private Pedido pedido;

    @Column(nullable = false, length = 40)
    private String productoId;

    @Column(nullable = false, length = 120)
    private String nombre;

    @Column(nullable = false)
    private long precioUnitario;

    @Column(nullable = false)
    private int cantidad;

    @Column(length = 40)
    private String categoria;

    @Column(length = 40)
    private String subcategoria;

    @Column(length = 60)
    private String marca;
}
