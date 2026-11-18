package com.kafka.telemetria;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Lambda Conexiones — el portero.
 *
 * API Gateway NO guarda la lista de clientes conectados: entrega un
 * connectionId en el momento de conectar y despues hay que saber a quien
 * escribirle. Esta funcion mantiene esa lista en DynamoDB, atendiendo las rutas
 * $connect y $disconnect de la API WebSocket.
 *
 * Responde a las dos rutas con una sola funcion porque comparten tabla y
 * permisos; la ruta llega en requestContext.routeKey.
 */
public class ConexionesHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    /* Los clientes se crean UNA vez, fuera del handler: las invocaciones
       reutilizan el contenedor, y crearlos en cada llamada pagaria el costo de
       inicializacion del SDK en cada mensaje. */
    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .build();

    private static final String TABLA = Entorno.requerido(Entorno.TABLA_CONEXIONES);

    /** Las conexiones viejas se barren solas; API Gateway corta a los 10 min de inactividad. */
    private static final long TTL_HORAS = 2;

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> evento, Context context) {
        Map<String, Object> requestContext = contextoDe(evento);
        String ruta = String.valueOf(requestContext.get("routeKey"));
        String connectionId = String.valueOf(requestContext.get("connectionId"));

        try {
            switch (ruta) {
                case "$connect" -> registrar(connectionId, clientIdDe(evento), context);
                case "$disconnect" -> borrar(connectionId, context);
                default -> context.getLogger().log("Ruta no manejada: " + ruta + "\n");
            }
        } catch (RuntimeException e) {
            context.getLogger().log("Error en la ruta " + ruta + ": " + e.getMessage() + "\n");
            /* Un 500 en $connect hace que API Gateway rechace la conexion, que es
               el comportamiento correcto: mejor que el navegador se entere ahora
               que quede conectado a una lista donde nunca fue anotado. */
            return respuesta(500, "error");
        }

        return respuesta(200, "ok");
    }

    private void registrar(String connectionId, String clientId, Context context) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("connectionId", AttributeValue.fromS(connectionId));
        item.put("conectadoEn", AttributeValue.fromS(Instant.now().toString()));
        item.put("expiraEn", AttributeValue.fromN(
                String.valueOf(Instant.now().plusSeconds(TTL_HORAS * 3600).getEpochSecond())));
        if (clientId != null && !clientId.isBlank()) {
            item.put("clientId", AttributeValue.fromS(clientId));
        }

        DYNAMO.putItem(PutItemRequest.builder().tableName(TABLA).item(item).build());
        context.getLogger().log("Conexion registrada: " + connectionId + "\n");
    }

    private void borrar(String connectionId, Context context) {
        DYNAMO.deleteItem(DeleteItemRequest.builder()
                .tableName(TABLA)
                .key(Map.of("connectionId", AttributeValue.fromS(connectionId)))
                .build());
        context.getLogger().log("Conexion borrada: " + connectionId + "\n");
    }

    /** El front conecta con ?clientId=operaciones_<ts>; se guarda para poder auditar quien miraba. */
    @SuppressWarnings("unchecked")
    private String clientIdDe(Map<String, Object> evento) {
        Object params = evento.get("queryStringParameters");
        if (params instanceof Map<?, ?> mapa) {
            Object valor = ((Map<String, Object>) mapa).get("clientId");
            return valor == null ? null : String.valueOf(valor);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contextoDe(Map<String, Object> evento) {
        Object ctx = evento.get("requestContext");
        if (ctx instanceof Map<?, ?> mapa) {
            return (Map<String, Object>) mapa;
        }
        throw new IllegalArgumentException("El evento no trae requestContext");
    }

    private Map<String, Object> respuesta(int codigo, String cuerpo) {
        return Map.of("statusCode", codigo, "body", cuerpo);
    }
}
