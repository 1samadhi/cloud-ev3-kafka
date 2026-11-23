package com.pedidos360.productos.Component;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.pedidos360.productos.Config.KafkaTopicosProperties;
import com.pedidos360.productos.DTO.ProductoCatalogoDTO;
import com.pedidos360.productos.Service.ServicioCatalogo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Al arrancar publica el catálogo completo en el tópico compactado (§7.2).
 *
 * ApplicationReadyEvent y no @PostConstruct: llega después de la semilla
 * (CommandLineRunner), así el primer arranque contra una base vacía igual publica los 20
 * productos. Republicar snapshots idénticos es inocuo: la compactación se queda con uno.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicadorCatalogoInicial {

    private final ServicioCatalogo servicioCatalogo;
    private final ProductorCatalogo productorCatalogo;
    private final KafkaTopicosProperties propiedades;

    @EventListener(ApplicationReadyEvent.class)
    public void publicarCatalogoCompleto() {
        if (!propiedades.isPublicarCatalogoAlArrancar()) {
            log.info("Publicación inicial del catálogo desactivada (app.kafka.publicar-catalogo-al-arrancar=false)");
            return;
        }
        List<ProductoCatalogoDTO> snapshots = servicioCatalogo.snapshotsCatalogo();
        log.info("Publicando catálogo completo ({} productos) en {}", snapshots.size(), propiedades.getTopicos().getCatalogo());
        snapshots.forEach(productorCatalogo::publicar);
    }
}
