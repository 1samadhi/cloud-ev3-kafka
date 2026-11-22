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

/**
 * Línea del carrito. nombre y precioUnitario son una copia de lo que respondió ms-productos
 * (el cliente nunca fija precios). categoria/subcategoria/marca no están en §8, se guardan
 * para enriquecer los eventos AGREGAR_CARRITO/COMPRA (las recomendaciones calculan afinidad
 * con ellos) y para ventas por categoría del resumen admin.
 */
@Entity
@Table(name = "item_carrito")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemCarrito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "carrito_usuario_id")
    private Carrito carrito;

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
