package com.kafka.recomendaciones.Component;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.Service.MetricasEnVivo;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * grupo-dashboard sobre el MISMO topico interacciones-user: otro grupo = offsets
 * independientes, recibe todos los mensajes que tambien recibe
 * grupo-recomendaciones (FAN-OUT, criterio 8).
 *
 * concurrency 1 y offset latest: solo cuenta en memoria lo que pasa ahora; un
 * hilo basta y evita contencion sobre las ventanas. Sus fallos NO van al DLT
 * (ver fabricaListenerDashboard): se descartan con WARN.
 */
@Slf4j
@Component
public class ConsumidorDashboard {

    private final ValidadorMensajes validador;
    private final MetricasEnVivo metricas;

    public ConsumidorDashboard(ValidadorMensajes validador, MetricasEnVivo metricas) {
        this.validador = validador;
        this.metricas = metricas;
    }

    @KafkaListener(
            id = "consumidor-dashboard",
            topics = "#{@kafkaTopicosProperties.topicos.interacciones}",
            groupId = "#{@kafkaTopicosProperties.grupos.dashboard}",
            containerFactory = "fabricaListenerDashboard",
            concurrency = "#{@kafkaTopicosProperties.concurrencia.dashboard}",
            clientIdPrefix = "ms-recomendaciones-dashboard")
    public void consumir(ConsumerRecord<String, EventoInteraccionDTO> registro) {
        // DEBUG y no INFO: grupo-recomendaciones ya loguea cada mensaje en INFO;
        // repetirlo duplicaria el log del clickstream sin agregar informacion.
        log.debug("Interaccion recibida [grupo-dashboard] topico={} particion={} offset={} key={}",
                registro.topic(), registro.partition(), registro.offset(), registro.key());
        metricas.registrar(validador.validar(registro, "EventoInteraccionDTO"));
    }
}
