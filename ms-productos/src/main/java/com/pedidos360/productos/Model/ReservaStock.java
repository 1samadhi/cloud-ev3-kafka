package com.pedidos360.productos.Model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

/**
 * Registro de la reserva de un pedido (tabla {@code reserva_stock}, §7.2).
 *
 * La PK es el idPedido: es lo que vuelve idempotentes a reservas y liberaciones. Una
 * segunda reserva del mismo pedido choca con la PK y una segunda liberación encuentra el
 * estado LIBERADA, así que ningún reintento de ms-carrito descuenta o devuelve dos veces.
 * Se guardan las cantidades reservadas para que la liberación devuelva exactamente lo
 * que se descontó, no lo que diga el cuerpo de la petición.
 */
@Entity
@Table(name = "reserva_stock")
@Getter
@NoArgsConstructor
public class ReservaStock {

    @Id
    @Column(name = "id_pedido", length = 64)
    private String idPedido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoReserva estado;

    @Column(nullable = false)
    private Instant creadaEn;

    private Instant liberadaEn;

    /**
     * Además de proteger contra liberaciones concurrentes, una versión nula es lo que hace
     * que Spring Data trate a la entidad como NUEVA (persist) aun teniendo id asignado; sin
     * esto save() haría merge y podría sobrescribir una reserva existente.
     */
    @Version
    private Long version;

    @OneToMany(mappedBy = "reserva", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("productoId ASC")
    private List<ItemReservaStock> items = new ArrayList<>();

    public static ReservaStock nueva(String idPedido, Map<String, Integer> cantidades, Instant ahora) {
        ReservaStock r = new ReservaStock();
        r.idPedido = idPedido;
        r.estado = EstadoReserva.RESERVADA;
        r.creadaEn = ahora;
        cantidades.forEach((productoId, cantidad) -> r.items.add(new ItemReservaStock(r, productoId, cantidad)));
        return r;
    }

    public void marcarLiberada(Instant ahora) {
        if (estado == EstadoReserva.LIBERADA) {
            throw new IllegalStateException("La reserva " + idPedido + " ya estaba liberada");
        }
        this.estado = EstadoReserva.LIBERADA;
        this.liberadaEn = ahora;
    }

    /** Cantidades por producto, ordenadas por id (el mismo orden en que se bloquean filas). */
    public Map<String, Integer> cantidadesPorProducto() {
        Map<String, Integer> m = new TreeMap<>();
        items.forEach(i -> m.merge(i.getProductoId(), i.getCantidad(), Integer::sum));
        return m;
    }
}
