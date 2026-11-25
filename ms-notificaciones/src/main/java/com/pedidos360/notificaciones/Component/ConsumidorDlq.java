package com.pedidos360.notificaciones.Component;

import com.pedidos360.notificaciones.DTO.MensajeMuertoDTO;
import com.pedidos360.notificaciones.Service.ServicioMensajesFallidos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Consumidor de la DLQ (notificaciones.cliente.dlq).
 *
 * RabbitMQ agrega a cada mensaje muerto el header x-death: una lista (el más
 * reciente primero) con la cola de la que murió, el motivo (rejected = lo
 * rechazó el consumidor; delivery_limit = superó x-delivery-limit; expired;
 * maxlen), cuántas veces y su routing key original. Eso es lo que convierte la
 * DLQ en algo diagnosticable y no en un basurero: se loguea en ERROR y se guarda
 * en mensaje_fallido.
 *
 * Usa fabricaDlq: si falla guardar, el mensaje vuelve a la DLQ (no se pierde).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumidorDlq {

    static final String DESCONOCIDO = "desconocido";
    private static final int MAX_CUERPO = 4000;

    private final ServicioMensajesFallidos servicioMensajesFallidos;

    @RabbitListener(id = "consumidor-dlq",
            queues = "#{@topologiaRabbitProperties.colaDlq}",
            containerFactory = "fabricaDlq")
    public void recibir(Message mensaje) {
        MensajeMuertoDTO muerto = extraer(mensaje);
        log.error("MENSAJE MUERTO en DLQ: colaOrigen={} motivo={} conteo={} exchangeOrigen={} routingKeys={} messageId={} cuerpo={}",
                muerto.colaOrigen(), muerto.motivo(), muerto.conteo(), muerto.exchangeOrigen(),
                muerto.routingKeys(), muerto.messageId(), muerto.cuerpo());
        servicioMensajesFallidos.registrar(muerto);
    }

    /**
     * Lee x-death de forma defensiva: un mensaje publicado a mano directo en la
     * DLQ no lo trae, y esta clase no puede lanzar por eso (se reencolaría para
     * siempre).
     */
    static MensajeMuertoDTO extraer(Message mensaje) {
        MessageProperties props = mensaje.getMessageProperties();
        String cuerpo = mensaje.getBody() == null ? "" : new String(mensaje.getBody(), StandardCharsets.UTF_8);
        if (cuerpo.length() > MAX_CUERPO) {
            cuerpo = cuerpo.substring(0, MAX_CUERPO);
        }

        List<Map<String, ?>> xDeath = props.getXDeathHeader();
        if (xDeath == null || xDeath.isEmpty()) {
            return new MensajeMuertoDTO(props.getMessageId(), DESCONOCIDO, "sin-x-death", 0,
                    DESCONOCIDO, List.of(), cuerpo);
        }

        // La primera entrada es la muerte más reciente.
        Map<String, ?> ultima = xDeath.get(0);
        return new MensajeMuertoDTO(
                props.getMessageId(),
                texto(ultima.get("queue")),
                texto(ultima.get("reason")),
                ultima.get("count") instanceof Number n ? n.longValue() : 0L,
                texto(ultima.get("exchange")),
                routingKeys(ultima.get("routing-keys")),
                cuerpo);
    }

    private static String texto(Object valor) {
        return valor == null ? DESCONOCIDO : valor.toString();
    }

    private static List<String> routingKeys(Object valor) {
        List<String> claves = new ArrayList<>();
        if (valor instanceof List<?> lista) {
            lista.forEach(v -> claves.add(String.valueOf(v)));
        }
        return claves;
    }
}
