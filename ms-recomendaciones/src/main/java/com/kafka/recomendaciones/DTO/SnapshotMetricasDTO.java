package com.kafka.recomendaciones.DTO;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Snapshot de metricas en vivo: el MISMO objeto sale por el WebSocket /ws/metricas
 * (cada 2 s), por GET /api/metricas y, convertido a item, a EventosOperaciones.
 *
 * Un solo formato para las tres salidas evita que la consola tenga que conocer
 * tres variantes del mismo dato. Los campos historicos (productosMasVistos,
 * totalInteracciones) se conservan porque la pantalla de Operaciones los lee.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({"tipo", "timestamp", "interaccionesPorMinuto", "serieMinutos", "topProductos",
        "usuariosActivos", "embudo", "porTipo", "totalInteracciones", "productosMasVistos"})
public class SnapshotMetricasDTO {

    public static final String TIPO = "METRICAS";

    /** Siempre "METRICAS": permite al cliente distinguir este mensaje de otros. */
    @Builder.Default
    private String tipo = TIPO;
    private long timestamp;

    /** Interacciones de cualquier tipo en los ultimos 60 s. */
    private long interaccionesPorMinuto;
    /** 15 puntos, uno por minuto, del mas antiguo al actual. */
    private List<PuntoSerie> serieMinutos;
    /** Top 5 por VER_PRODUCTO en los ultimos 15 min. */
    private List<TopProducto> topProductos;
    /** Usuarios distintos en los ultimos 5 min. */
    private long usuariosActivos;
    /** Vistas -> carrito -> compra en los ultimos 15 min. */
    private Embudo embudo;
    /** Eventos por tipo en los ultimos 15 min (siempre con las 5 claves). */
    private Map<String, Long> porTipo;
    /** Eventos recibidos por el grupo-dashboard desde que arranco el servicio. */
    private long totalInteracciones;
    /** Mismo contenido que topProductos con la forma historica {itemId,nombre,categoria,vistas}. */
    private List<ProductoVistoDTO> productosMasVistos;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PuntoSerie {
        /** Inicio del minuto, epoch millis. */
        private long inicio;
        private long interacciones;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopProducto {
        private String productoId;
        private String nombre;
        private String categoria;
        private long vistas;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Embudo {
        private long vistas;
        private long carritos;
        private long compras;
    }
}
