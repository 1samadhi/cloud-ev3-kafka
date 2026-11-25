package com.pedidos360.rabbitadmin.DTO;

import java.util.List;

/**
 * Estado de un nodo. Las métricas son null cuando el nodo está caído (la API
 * no las informa); porcentajeMemoria es memoriaUsada / límite (high watermark),
 * no sobre la RAM del host: al llegar a 100 RabbitMQ frena a los publicadores.
 */
public record NodoDTO(
        String nombre,
        String tipo,
        boolean corriendo,
        Long memoriaUsadaBytes,
        Long memoriaLimiteBytes,
        Double porcentajeMemoria,
        boolean alarmaMemoria,
        Long discoLibreBytes,
        Long discoLimiteBytes,
        boolean alarmaDisco,
        List<String> particiones,
        Long uptimeMs) {
}
