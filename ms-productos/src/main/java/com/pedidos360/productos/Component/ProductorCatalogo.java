package com.pedidos360.productos.Component;

import java.util.concurrent.CompletableFuture;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.pedidos360.productos.Config.KafkaTopicosProperties;
import com.pedidos360.productos.DTO.ProductoCatalogoDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Productor del tópico compactado {@code catalogo-productos} (§3.2). Key = productoId:
 * la compactación conserva sólo el último snapshot por key, así que la key ES la identidad
 * del producto. Mandar un snapshot con otra key dejaría versiones viejas vivas para siempre.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductorCatalogo {

    private final KafkaTemplate<String, ProductoCatalogoDTO> plantillaCatalogo;
    private final KafkaTopicosProperties propiedades;

    public CompletableFuture<SendResult<String, ProductoCatalogoDTO>> publicar(ProductoCatalogoDTO snapshot) {
        String topico = propiedades.getTopicos().getCatalogo();
        String key = snapshot.getId();
        try {
            return plantillaCatalogo.send(topico, key, snapshot).whenComplete((resultado, error) -> {
                if (error == null) {
                    var meta = resultado.getRecordMetadata();
                    log.info("Snapshot publicado: topico={} particion={} offset={} key={} stock={} precio={}",
                            meta.topic(), meta.partition(), meta.offset(), key, snapshot.getStock(), snapshot.getPrecio());
                } else {
                    log.error("No se pudo publicar el snapshot: topico={} key={} stock={} causa={}",
                            topico, key, snapshot.getStock(), error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.error("Fallo inmediato al publicar el snapshot: topico={} key={} causa={}", topico, key, e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }
}
