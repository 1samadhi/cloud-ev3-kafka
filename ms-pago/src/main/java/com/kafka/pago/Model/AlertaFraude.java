package com.kafka.pago.Model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Registro histórico de cada bloqueo disparado. A diferencia de
 * bloqueo_tarjeta (estado actual, se sobrescribe), esta tabla sólo crece: es
 * la evidencia de cuándo y cuántas veces se detectó una ráfaga.
 */
@Entity
@Table(name = "alerta_fraude", indexes = @Index(name = "idx_alerta_huella", columnList = "huella"))
@Data
public class AlertaFraude {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "huella", length = 64, nullable = false)
    private String huella;

    @Column(name = "ultimos4", length = 4)
    private String ultimos4;

    @Column(name = "id_cliente", length = 150)
    private String idCliente;

    @Column(name = "intentos", nullable = false)
    private int intentos;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;
}
