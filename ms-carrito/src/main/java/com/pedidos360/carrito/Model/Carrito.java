package com.pedidos360.carrito.Model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Un carrito por usuario (la PK es el propio usuarioId). */
@Entity
@Table(name = "carrito")
@Getter
@Setter
@NoArgsConstructor
public class Carrito {

    @Id
    @Column(length = 64)
    private String usuarioId;

    @Column(length = 40)
    private String cupon;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private MetodoEnvio metodoEnvio;

    @Column(length = 200)
    private String direccion;

    @Column(length = 80)
    private String comuna;

    @Column(length = 120)
    private String sucursal;

    private Instant actualizadoEn;

    /** Dos pestañas del mismo usuario agregando a la vez no deben pisarse en silencio. */
    @Version
    private Long version;

    @OneToMany(mappedBy = "carrito", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ItemCarrito> items = new ArrayList<>();

    public Carrito(String usuarioId) {
        this.usuarioId = usuarioId;
    }

    public Optional<ItemCarrito> buscarItem(String productoId) {
        return items.stream().filter(i -> i.getProductoId().equals(productoId)).findFirst();
    }

    public void agregarItem(ItemCarrito item) {
        item.setCarrito(this);
        items.add(item);
    }

    public void quitarItem(String productoId) {
        items.removeIf(i -> i.getProductoId().equals(productoId));
    }

    /** Vacía ítems y cupón; los datos de envío se conservan para la próxima compra. */
    public void vaciar() {
        items.clear();
        cupon = null;
    }
}
