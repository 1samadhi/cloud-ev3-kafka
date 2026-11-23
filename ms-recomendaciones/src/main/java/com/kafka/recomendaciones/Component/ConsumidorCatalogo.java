package com.kafka.recomendaciones.Component;

import com.kafka.recomendaciones.DTO.ProductoCatalogoDTO;
import com.kafka.recomendaciones.Model.Producto;
import com.kafka.recomendaciones.Service.CatalogoEnMemoria;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * grupo-catalogo-recomendaciones sobre catalogo-productos (compactado, earliest):
 * al arrancar se lee el topico entero y queda el ultimo snapshot de cada
 * producto, sin consultar a ms-productos por REST.
 */
@Slf4j
@Component
public class ConsumidorCatalogo {

    private final ValidadorMensajes validador;
    private final CatalogoEnMemoria catalogo;

    public ConsumidorCatalogo(ValidadorMensajes validador, CatalogoEnMemoria catalogo) {
        this.validador = validador;
        this.catalogo = catalogo;
    }

    @KafkaListener(
            id = "consumidor-catalogo",
            topics = "#{@kafkaTopicosProperties.topicos.catalogo}",
            groupId = "#{@kafkaTopicosProperties.grupos.catalogo}",
            containerFactory = "fabricaListenerCatalogo",
            concurrency = "#{@kafkaTopicosProperties.concurrencia.catalogo}",
            clientIdPrefix = "ms-recomendaciones-catalogo")
    public void consumir(ConsumerRecord<String, ProductoCatalogoDTO> registro) {
        log.info("Catalogo recibido topico={} particion={} offset={} key={}",
                registro.topic(), registro.partition(), registro.offset(), registro.key());
        // Un JSON roto nunca llega aca (el contenedor lo manda al DLT antes), asi
        // que valor null con key es un tombstone legitimo: producto eliminado.
        if (registro.value() == null && registro.key() != null) {
            catalogo.eliminar(registro.key());
            log.info("Producto {} eliminado del catalogo (tombstone)", registro.key());
            return;
        }
        ProductoCatalogoDTO dto = validador.validar(registro, "ProductoCatalogoDTO");
        catalogo.actualizar(Producto.desde(dto));
    }
}
