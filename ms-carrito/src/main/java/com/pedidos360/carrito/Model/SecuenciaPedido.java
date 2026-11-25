package com.pedidos360.carrito.Model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Genera el <n> de "P-<n>". JPA sólo autogenera claves primarias, y el id del pedido es un
 * String: insertar una fila aquí y usar su IDENTITY es seguro con varias réplicas del servicio
 * (el contador lo lleva MySQL, no la memoria de una instancia). Los huecos tras un rollback
 * son aceptables: el id sólo tiene que ser único.
 */
@Entity
@Table(name = "secuencia_pedido")
@Getter
@Setter
@NoArgsConstructor
public class SecuenciaPedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant creadoEn;

    public SecuenciaPedido(Instant creadoEn) {
        this.creadoEn = creadoEn;
    }
}
