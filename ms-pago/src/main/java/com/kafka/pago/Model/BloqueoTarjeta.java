package com.kafka.pago.Model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Estado de bloqueo por huella. Una fila por tarjeta (o cuenta, con Webpay);
 * se reutiliza en bloqueos sucesivos y "vigente" significa bloqueadoHasta > ahora.
 *
 * idCliente es una extensión del contrato (§4.2 no la lista): la consola marca
 * "cliente bloqueado" comparando idCliente de /api/alertas, y guardarlo acá
 * evita buscarlo en alerta_fraude para cada fila.
 */
@Entity
@Table(name = "bloqueo_tarjeta")
@Data
public class BloqueoTarjeta {

    @Id
    @Column(name = "huella", length = 64)
    private String huella;

    @Column(name = "ultimos4", length = 4)
    private String ultimos4;

    @Column(name = "id_cliente", length = 150)
    private String idCliente;

    @Column(name = "intentos", nullable = false)
    private int intentos;

    @Column(name = "bloqueado_hasta")
    private Instant bloqueadoHasta;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    public boolean estaVigente(Instant ahora) {
        return bloqueadoHasta != null && bloqueadoHasta.isAfter(ahora);
    }
}
