package com.pedidos360.productos.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Categoría del catálogo (slug · etiqueta de §7.1). Vive en la base y no en un enum
 * para que agregar una categoría no obligue a recompilar ni a redeplegar.
 */
@Entity
@Table(name = "categoria")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Categoria {

    @Id
    @Column(length = 40)
    private String slug;

    @Column(nullable = false, length = 60)
    private String etiqueta;

    /** Orden de despliegue en la tienda. */
    @Column(nullable = false)
    private int orden;
}
