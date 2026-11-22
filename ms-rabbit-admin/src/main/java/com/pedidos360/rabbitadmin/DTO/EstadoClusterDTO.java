package com.pedidos360.rabbitadmin.DTO;

import java.util.List;

/**
 * Vista del clúster para GET /api/rabbit/cluster.
 *
 * hayQuorum: más de la mitad de los nodos corriendo. Es la condición para que
 * las colas quorum (Raft) acepten escrituras: con 3 nodos se tolera 1 caído.
 * hayParticiones: algún nodo detectó una partición de red (con pause_minority
 * el lado minoritario se pausa solo).
 * fuente: URL de management que respondió; si no es la primera, hubo failover.
 */
public record EstadoClusterDTO(
        String nombreCluster,
        int totalNodos,
        int nodosCorriendo,
        boolean hayQuorum,
        boolean hayParticiones,
        boolean hayAlarmas,
        List<NodoDTO> nodos,
        String fuente,
        long consultadoEn) {
}
