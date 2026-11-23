package com.kafka.admin.DTO;

/**
 * A dónde mover los offsets de un consumer group. Son las mismas opciones de
 * kafka-consumer-groups --reset-offsets que tienen sentido desde una consola.
 */
public enum EstrategiaReset {
    /** Al primer mensaje que aún retiene el tópico: reprocesar todo. */
    EARLIEST,
    /** Al final: saltarse el atraso y procesar sólo lo nuevo. */
    LATEST,
    /** Al primer mensaje con timestamp >= valor (epoch ms): reprocesar desde un incidente. */
    TIMESTAMP,
    /** A un offset exacto (se ajusta al rango que existe). */
    OFFSET
}
