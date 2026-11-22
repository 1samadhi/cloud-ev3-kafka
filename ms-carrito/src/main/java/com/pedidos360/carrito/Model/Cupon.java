package com.pedidos360.carrito.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "cupon")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cupon {

    @Id
    @Column(length = 40)
    private String codigo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TipoCupon tipo;

    /** Porcentaje o monto según el tipo; ignorado en ENVIO_GRATIS. */
    @Column(nullable = false)
    private long valor;

    @Column(nullable = false)
    private long minimoCompra;

    @Column(nullable = false)
    private boolean activo;

    @Column(length = 200)
    private String descripcion;
}
