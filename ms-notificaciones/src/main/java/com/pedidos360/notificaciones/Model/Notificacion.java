package com.pedidos360.notificaciones.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Map;

/**
 * Notificación entregada a un usuario.
 *
 * El id es el idNotificacion (UUID) del productor y no uno autogenerado: RabbitMQ
 * garantiza "al menos una vez", así que el mismo mensaje puede llegar dos veces
 * (reentrega tras una caída). Con el UUID como PK, el duplicado choca con la
 * clave primaria y se descarta: idempotencia sin tabla extra.
 *
 * Persistable: con un id asignado, Spring Data haría merge (UPDATE silencioso)
 * en vez de INSERT; así el duplicado concurrente se detecta como violación de PK.
 */
@Entity
@Table(name = "notificacion", indexes = {
        @Index(name = "idx_notificacion_usuario_fecha", columnList = "usuarioId, creadaEn")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notificacion implements Persistable<String> {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String usuarioId;

    @Column(nullable = false, length = 64)
    private String tipo;

    @Column(nullable = false, length = 200)
    private String titulo;

    @Column(nullable = false, length = 1000)
    private String mensaje;

    @Column(length = 64)
    private String idPedido;

    @Convert(converter = MapaJsonConverter.class)
    @Column(length = 4000)
    private Map<String, Object> datos;

    private boolean leida;

    @Column(nullable = false)
    private Instant creadaEn;

    @Transient
    @Builder.Default
    private boolean nueva = true;

    @Override
    public boolean isNew() {
        return nueva;
    }

    @PostLoad
    @PostPersist
    void marcarExistente() {
        this.nueva = false;
    }
}
