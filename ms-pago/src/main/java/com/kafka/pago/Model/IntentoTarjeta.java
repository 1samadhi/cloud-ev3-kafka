package com.kafka.pago.Model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Un intento de pago visto por el antifraude. Es la materia prima de la
 * ventana deslizante: se cuentan las filas de una huella dentro de los últimos
 * 10 s, en lugar de un contador que sólo recuerda el último intento.
 *
 * El índice (huella, instante) es exactamente el de la consulta de la ventana.
 * id_intento (único) es una extensión del contrato: hace idempotente el
 * consumo, porque Kafka entrega "al menos una vez" y un reintento no debe
 * contar dos veces el mismo intento.
 */
@Entity
@Table(name = "intento_tarjeta", indexes = {
        @Index(name = "idx_intento_huella_instante", columnList = "huella, instante"),
        @Index(name = "idx_intento_instante", columnList = "instante")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntentoTarjeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_intento", length = 64, nullable = false, unique = true)
    private String idIntento;

    @Column(name = "huella", length = 64, nullable = false)
    private String huella;

    @Column(name = "instante", nullable = false)
    private Instant instante;
}
