package com.kafka.recomendaciones.Service;

import com.kafka.recomendaciones.Component.ProductorInteracciones;
import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.InteractionRequestsDTO;
import org.springframework.stereotype.Service;

/**
 * Entrada HTTP de interacciones: traduce el cuerpo viejo y publica el evento.
 *
 * El productor solo publica; el unico que persiste es el consumidor. Si ambos
 * guardaran, cada interaccion quedaria duplicada (el error que tenia la guia).
 */
@Service
public class ServicioInteracciones {

    private final TraductorInteracciones traductor;
    private final ProductorInteracciones productor;

    public ServicioInteracciones(TraductorInteracciones traductor, ProductorInteracciones productor) {
        this.traductor = traductor;
        this.productor = productor;
    }

    public EventoInteraccionDTO registrar(InteractionRequestsDTO peticion) {
        EventoInteraccionDTO evento = traductor.traducir(peticion);
        productor.publicar(evento);
        return evento;
    }
}
