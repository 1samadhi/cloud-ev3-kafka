package com.kafka.recomendaciones.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Registro de mensajes muertos (DLT). Loguea ERROR con todo el contexto y guarda
 * los ultimos N en memoria para inspeccion y pruebas. No persiste: el DLT mismo
 * retiene 14 dias, que es la fuente para reprocesar.
 */
@Slf4j
@Service
public class ServicioMensajesMuertos {

    static final int MAXIMO_EN_MEMORIA = 100;

    public record MensajeMuerto(String topicoDlt, String topicoOriginal, Integer particionOriginal,
                                Long offsetOriginal, String tipoExcepcion, String causa, String key,
                                String valor, long recibidoEn) {
    }

    private final Deque<MensajeMuerto> ultimos = new ArrayDeque<>();

    public synchronized void registrar(MensajeMuerto m) {
        log.error("Mensaje muerto en {}: original={} particion={} offset={} key={} excepcion={} causa={} valor={}",
                m.topicoDlt(), m.topicoOriginal(), m.particionOriginal(), m.offsetOriginal(), m.key(),
                m.tipoExcepcion(), m.causa(), m.valor());
        ultimos.addLast(m);
        if (ultimos.size() > MAXIMO_EN_MEMORIA) {
            ultimos.removeFirst();
        }
    }

    public synchronized List<MensajeMuerto> ultimos() {
        return new ArrayList<>(ultimos);
    }
}
