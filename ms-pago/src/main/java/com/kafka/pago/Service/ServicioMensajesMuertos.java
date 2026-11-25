package com.kafka.pago.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Registro de los mensajes que terminaron en el DLT. Loguea ERROR con el
 * contexto original (tópico/partición/offset/excepción) y guarda los últimos
 * 100 en memoria: alcanza para inspeccionarlos en la demo y para las pruebas,
 * sin sumar otra tabla.
 */
@Slf4j
@Service
public class ServicioMensajesMuertos {

    private static final int MAXIMO = 100;
    private final Deque<MensajeMuerto> recientes = new ConcurrentLinkedDeque<>();

    public void registrar(MensajeMuerto mensaje) {
        log.error("Mensaje en DLT: origen topico={} particion={} offset={} key={} excepcion=\"{}\" valor={}",
                mensaje.topicoOriginal(), mensaje.particionOriginal(), mensaje.offsetOriginal(), mensaje.key(),
                mensaje.mensajeExcepcion(), mensaje.valor());
        recientes.addFirst(mensaje);
        while (recientes.size() > MAXIMO) {
            recientes.pollLast();
        }
    }

    public List<MensajeMuerto> recientes() {
        return new ArrayList<>(recientes);
    }

    public record MensajeMuerto(String topicoOriginal, Integer particionOriginal, Long offsetOriginal,
                                String mensajeExcepcion, String key, String valor, Instant recibidoEn) {
    }
}
