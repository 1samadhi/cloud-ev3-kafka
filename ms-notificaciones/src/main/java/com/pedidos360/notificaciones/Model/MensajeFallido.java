package com.pedidos360.notificaciones.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Registro de un mensaje que terminó en la DLQ. El log ERROR se pierde al rotar;
 * esta tabla es la evidencia consultable (GET /api/notificaciones/fallidas).
 */
@Entity
@Table(name = "mensaje_fallido")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MensajeFallido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128)
    private String messageId;

    @Column(length = 255)
    private String colaOrigen;

    @Column(length = 255)
    private String exchangeOrigen;

    @Column(length = 500)
    private String routingKeys;

    /** rejected | delivery_limit | expired | maxlen (valores de RabbitMQ). */
    @Column(length = 64)
    private String motivo;

    private long conteo;

    @Column(length = 4000)
    private String cuerpo;

    @Column(nullable = false)
    private Instant recibidoEn;
}
