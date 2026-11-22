package com.pedidos360.productos.Model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Producto del catálogo con su stock.
 *
 * {@code @Version}: concurrencia optimista como segunda red. La primera es el bloqueo
 * pesimista que toman las reservas (ver ProductoRepositorio#bloquearPorIds); si algún
 * camino futuro modificara el stock sin bloquear, la versión impide que dos escrituras
 * concurrentes se pisen en silencio (lost update).
 */
@Entity
@Table(name = "producto", indexes = @Index(name = "idx_producto_categoria", columnList = "categoria"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Producto {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 120)
    private String nombre;

    @Column(length = 60)
    private String marca;

    /** Slug de la categoría (FK lógica a Categoria.slug). */
    @Column(nullable = false, length = 40)
    private String categoria;

    @Column(length = 60)
    private String subcategoria;

    /** CLP sin decimales: el peso chileno no tiene centavos. */
    @Column(nullable = false)
    private Long precio;

    @Column(nullable = false)
    private Integer stock;

    private Double calificacion;

    @Column(length = 500)
    private String imagenUrl;

    @Column(length = 500)
    private String descripcion;

    @Column(nullable = false)
    private Instant actualizadoEn;

    @Version
    private Long version;

    /**
     * Descuenta stock. El servicio ya verificó TODOS los ítems antes de llamar a esto;
     * la verificación repetida aquí es defensa en profundidad: el stock jamás puede
     * quedar negativo aunque alguien llame mal al método.
     */
    public void descontarStock(int cantidad, Instant ahora) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad a descontar debe ser positiva");
        }
        if (stock < cantidad) {
            throw new IllegalStateException("Stock insuficiente para " + id + ": disponible " + stock + ", pedido " + cantidad);
        }
        this.stock = stock - cantidad;
        this.actualizadoEn = ahora;
    }

    public void reponerStock(int cantidad, Instant ahora) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad a reponer debe ser positiva");
        }
        this.stock = Math.addExact(stock, cantidad);
        this.actualizadoEn = ahora;
    }
}
