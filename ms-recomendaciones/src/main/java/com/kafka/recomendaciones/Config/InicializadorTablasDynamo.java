package com.kafka.recomendaciones.Config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;

import java.util.ArrayList;
import java.util.List;

/**
 * Crea las tablas al arrancar si no existen (app.dynamodb.crear-tablas=true).
 *
 * Corre en afterPropertiesSet, ANTES de que arranquen los listeners de Kafka: si
 * corriera despues, el primer evento podria llegar a una tabla inexistente y
 * gastar reintentos. Si DynamoDB no responde se loguea ERROR y el servicio sigue
 * arriba: metricas y WebSocket no dependen de DynamoDB, y los eventos que fallen
 * al persistir se reintentan y terminan en el DLT, no se pierden en silencio.
 *
 * Se usa el cliente de bajo nivel (y no el enhanced) porque permite fijar
 * PAY_PER_REQUEST, Streams y TTL, que el enhanced no expone.
 */
@Slf4j
@Component
@ConditionalOnExpression("${app.dynamodb.habilitado:true} and ${app.dynamodb.crear-tablas:true}")
public class InicializadorTablasDynamo implements InitializingBean {

    private final DynamoDbClient cliente;
    private final String tablaHistorial;
    private final String tablaRecomendaciones;
    private final String tablaOperaciones;

    public InicializadorTablasDynamo(DynamoDbClient cliente,
                                     @Value("${app.dynamodb.tablas.historial}") String tablaHistorial,
                                     @Value("${app.dynamodb.tablas.recomendaciones}") String tablaRecomendaciones,
                                     @Value("${app.dynamodb.tablas.operaciones}") String tablaOperaciones) {
        this.cliente = cliente;
        this.tablaHistorial = tablaHistorial;
        this.tablaRecomendaciones = tablaRecomendaciones;
        this.tablaOperaciones = tablaOperaciones;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            crearSiNoExiste(tablaHistorial, "usuarioId", "instanteEvento", false);
            crearSiNoExiste(tablaRecomendaciones, "usuarioId", null, false);
            // Mismas claves, stream y TTL que infra/terraform-telemetria/dynamodb.tf,
            // para que local y AWS tengan la misma forma.
            if (crearSiNoExiste(tablaOperaciones, "tipo", "ocurridoEn", true)) {
                activarTtl(tablaOperaciones, "expiraEn");
            }
        } catch (SdkException e) {
            log.error("No se pudieron verificar/crear las tablas de DynamoDB: {}. "
                    + "El servicio sigue; la persistencia fallara hasta que DynamoDB responda.", e.getMessage());
        }
    }

    /** @return true si la creo en esta llamada. */
    boolean crearSiNoExiste(String tabla, String claveParticion, String claveOrden, boolean conStream) {
        if (existe(tabla)) {
            log.info("Tabla DynamoDB {} ya existe", tabla);
            return false;
        }
        List<AttributeDefinition> atributos = new ArrayList<>();
        List<KeySchemaElement> claves = new ArrayList<>();
        atributos.add(AttributeDefinition.builder().attributeName(claveParticion).attributeType(ScalarAttributeType.S).build());
        claves.add(KeySchemaElement.builder().attributeName(claveParticion).keyType(KeyType.HASH).build());
        if (claveOrden != null) {
            atributos.add(AttributeDefinition.builder().attributeName(claveOrden).attributeType(ScalarAttributeType.S).build());
            claves.add(KeySchemaElement.builder().attributeName(claveOrden).keyType(KeyType.RANGE).build());
        }

        CreateTableRequest.Builder peticion = CreateTableRequest.builder()
                .tableName(tabla)
                .attributeDefinitions(atributos)
                .keySchema(claves)
                // Carga a rafagas de demo: con capacidad fija habria que elegir entre
                // pagar de mas o recibir throttling (mismo criterio que el Terraform).
                .billingMode(BillingMode.PAY_PER_REQUEST);
        if (conStream) {
            // NEW_IMAGE: la Lambda solo lee el dato nuevo.
            peticion.streamSpecification(StreamSpecification.builder()
                    .streamEnabled(true).streamViewType(StreamViewType.NEW_IMAGE).build());
        }
        cliente.createTable(peticion.build());
        cliente.waiter().waitUntilTableExists(r -> r.tableName(tabla));
        log.info("Tabla DynamoDB {} creada (PK {}{}{})", tabla, claveParticion,
                claveOrden != null ? ", SK " + claveOrden : "", conStream ? ", stream NEW_IMAGE" : "");
        return true;
    }

    private boolean existe(String tabla) {
        try {
            cliente.describeTable(r -> r.tableName(tabla));
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    private void activarTtl(String tabla, String atributo) {
        try {
            cliente.updateTimeToLive(r -> r.tableName(tabla).timeToLiveSpecification(
                    TimeToLiveSpecification.builder().attributeName(atributo).enabled(true).build()));
        } catch (SdkException e) {
            // Sin TTL la tabla funciona igual, solo no se limpia sola.
            log.warn("No se pudo activar el TTL en {}: {}", tabla, e.getMessage());
        }
    }
}
