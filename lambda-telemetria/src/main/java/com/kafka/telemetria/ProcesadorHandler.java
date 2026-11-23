package com.kafka.telemetria;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
/* Ojo: este AttributeValue es el del SDK v2 y se usa para la tabla de
   conexiones. El de la imagen del stream es otra clase
   (events.models.dynamodb.AttributeValue) y lo maneja EventoOperaciones. */
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lambda Procesador — el que empuja.
 *
 * La invoca DynamoDB Streams con el lote de eventos recien escritos, los
 * convierte al formato del dashboard y los envia a todos los navegadores
 * conectados.
 *
 * Se envia UN mensaje con el lote completo y no uno por evento: con la ventana
 * de 1 segundo del trigger, un pico de interacciones llega agrupado, y mandar
 * cada lectura por separado multiplicaria los postToConnection (que se cobran
 * por mensaje) sin que el operador note la diferencia. El dashboard ya espera
 * un arreglo por este motivo.
 */
public class ProcesadorHandler implements RequestHandler<DynamodbEvent, Void> {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .build();

    /* El endpoint de administracion va en https:// aunque el navegador use
       wss://: es la misma API vista desde el otro lado. */
    private static final ApiGatewayManagementApiClient WEBSOCKET = ApiGatewayManagementApiClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .endpointOverride(URI.create(Entorno.requerido(Entorno.ENDPOINT_WEBSOCKET)))
            .build();

    private static final String TABLA_CONEXIONES = Entorno.requerido(Entorno.TABLA_CONEXIONES);

    @Override
    public Void handleRequest(DynamodbEvent evento, Context context) {
        List<Map<String, Object>> lote = new ArrayList<>();

        for (DynamodbStreamRecord registro : evento.getRecords()) {
            /* Interesan las escrituras, no los borrados: el stream esta en
               NEW_IMAGE y un REMOVE llega sin imagen nueva. */
            if (registro.getDynamodb() == null || registro.getDynamodb().getNewImage() == null) {
                continue;
            }
            lote.add(EventoOperaciones.aJson(registro.getDynamodb().getNewImage()));
        }

        if (lote.isEmpty()) {
            context.getLogger().log("Lote sin imagenes nuevas, nada que difundir\n");
            return null;
        }

        String payload;
        try {
            payload = JSON.writeValueAsString(lote);
        } catch (Exception e) {
            context.getLogger().log("No se pudo serializar el lote: " + e.getMessage() + "\n");
            return null;
        }

        difundir(payload, lote.size(), context);
        return null;
    }

    private void difundir(String payload, int eventos, Context context) {
        /* Scan y no Query: hay que escribirle a TODAS las conexiones, no a una
           clave. La tabla tiene una fila por navegador abierto, asi que su
           tamano es la cantidad de operadores mirando el tablero. */
        List<Map<String, AttributeValue>> conexiones =
                DYNAMO.scan(ScanRequest.builder().tableName(TABLA_CONEXIONES).build()).items();

        int enviados = 0;
        int caducas = 0;

        for (Map<String, AttributeValue> conexion : conexiones) {
            AttributeValue id = conexion.get("connectionId");
            if (id == null || id.s() == null) {
                continue;
            }
            try {
                WEBSOCKET.postToConnection(PostToConnectionRequest.builder()
                        .connectionId(id.s())
                        .data(SdkBytes.fromUtf8String(payload))
                        .build());
                enviados++;
            } catch (GoneException e) {
                /* 410 Gone: el navegador se fue sin que $disconnect alcanzara a
                   limpiar (pestana cerrada de golpe, red cortada, o el corte por
                   inactividad de API Gateway). Se borra aca o la tabla crece con
                   conexiones muertas a las que se intenta escribir para siempre. */
                borrarConexionCaduca(id.s(), context);
                caducas++;
            } catch (RuntimeException e) {
                context.getLogger().log("Fallo al escribir a " + id.s() + ": " + e.getMessage() + "\n");
            }
        }

        context.getLogger().log(String.format(
                "Lote de %d evento(s) difundido a %d conexion(es); %d caduca(s) eliminada(s)%n",
                eventos, enviados, caducas));
    }

    private void borrarConexionCaduca(String connectionId, Context context) {
        try {
            DYNAMO.deleteItem(DeleteItemRequest.builder()
                    .tableName(TABLA_CONEXIONES)
                    .key(Map.of("connectionId", AttributeValue.fromS(connectionId)))
                    .build());
        } catch (RuntimeException e) {
            context.getLogger().log("No se pudo borrar la conexion caduca " + connectionId + "\n");
        }
    }
}
