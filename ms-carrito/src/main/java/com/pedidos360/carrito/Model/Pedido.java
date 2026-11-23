package com.pedidos360.carrito.Model;

import com.pedidos360.carrito.Service.MaquinaEstadosPedido;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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

@Entity
@Table(name = "pedido", indexes = {
        @Index(name = "idx_pedido_usuario", columnList = "usuarioId"),
        @Index(name = "idx_pedido_estado", columnList = "estado")
})
@Getter
@Setter
@NoArgsConstructor
public class Pedido {

    /** "P-<n>": viaja como transactionId a ms-pago y vuelve como idOrden en pagos-resultado. */
    @Id
    @Column(length = 20)
    private String id;

    @Column(nullable = false, length = 64)
    private String usuarioId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoPedido estado;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MetodoEnvio metodoEnvio;

    @Column(nullable = false, length = 300)
    private String destino;

    private long subtotal;
    private long descuento;
    private long costoEnvio;
    private long total;

    @Column(length = 40)
    private String cupon;

    private Instant creadoEn;
    private Instant actualizadoEn;

    /** No está en §8: el resumen agrupa las ventas por el día del pago, no el de creación. */
    private Instant pagadoEn;

    @Column(length = 40)
    private String codigoAutorizacion;

    /**
     * Concurrencia optimista: el scheduler de envíos y el consumidor de pagos pueden tocar el
     * mismo pedido; el segundo en guardar falla y (en Kafka) se reintenta con el estado fresco.
     */
    @Version
    private Long version;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ItemPedido> items = new ArrayList<>();

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("instante ASC, id ASC")
    private List<HistorialEstadoPedido> historial = new ArrayList<>();

    public void agregarItem(ItemPedido item) {
        item.setPedido(this);
        items.add(item);
    }

    /** Estado inicial: no pasa por la máquina de estados porque no hay estado previo. */
    public void iniciar(Instant ahora) {
        this.estado = EstadoPedido.PENDIENTE_PAGO;
        this.creadoEn = ahora;
        this.actualizadoEn = ahora;
        registrarHistorial(EstadoPedido.PENDIENTE_PAGO, ahora);
    }

    /** Toda transición pasa por la máquina de estados: un salto inválido lanza excepción. */
    public void cambiarEstado(EstadoPedido nuevo, Instant ahora) {
        MaquinaEstadosPedido.validar(estado, nuevo, metodoEnvio);
        this.estado = nuevo;
        this.actualizadoEn = ahora;
        if (nuevo == EstadoPedido.PAGADO) {
            this.pagadoEn = ahora;
        }
        registrarHistorial(nuevo, ahora);
    }

    private void registrarHistorial(EstadoPedido e, Instant ahora) {
        HistorialEstadoPedido h = new HistorialEstadoPedido();
        h.setPedido(this);
        h.setEstado(e);
        h.setInstante(ahora);
        historial.add(h);
    }
}
