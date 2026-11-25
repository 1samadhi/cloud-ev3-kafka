package com.kafka.telemetria;

/* El modelo de Streams lo trae aws-lambda-java-events, NO el SDK: desde la
   version 3 la libreria dejo de arrastrar el SDK v1 de DynamoDB y publica sus
   propias clases en events.models.dynamodb. Importar la del SDK compila solo si
   alguien agrega esa dependencia vieja a mano. */
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduce un item de DynamoDB al formato que entiende el dashboard.
 *
 * La conversion existe porque son dos formas distintas del mismo dato: DynamoDB
 * guarda cada valor envuelto en su tipo ({"S": "..."} , {"N": "3"}) y el
 * navegador espera JSON plano. Enviar el item crudo obligaria al frontend a
 * desenvolver tipos de DynamoDB, que es acoplarlo a la base.
 *
 * El campo "tipo" se mantiene en la salida porque es lo que el dashboard usa
 * para decidir si el evento actualiza las metricas o la tabla de alertas.
 */
final class EventoOperaciones {

    private EventoOperaciones() {
    }

    /**
     * Desenvuelve recursivamente un item de Streams.
     *
     * Se usa LinkedHashMap para que el orden de las claves sea estable: hace
     * legibles los logs y los diffs al comparar payloads.
     */
    static Map<String, Object> aJson(Map<String, AttributeValue> item) {
        Map<String, Object> salida = new LinkedHashMap<>();
        item.forEach((clave, valor) -> salida.put(clave, desenvolver(valor)));
        return salida;
    }

    private static Object desenvolver(AttributeValue valor) {
        if (valor == null) {
            return null;
        }
        if (valor.getS() != null) {
            return valor.getS();
        }
        if (valor.getN() != null) {
            return numero(valor.getN());
        }
        if (valor.getBOOL() != null) {
            return valor.getBOOL();
        }
        if (Boolean.TRUE.equals(valor.getNULL())) {
            return null;
        }
        if (valor.getM() != null) {
            Map<String, Object> mapa = new LinkedHashMap<>();
            valor.getM().forEach((k, v) -> mapa.put(k, desenvolver(v)));
            return mapa;
        }
        if (valor.getL() != null) {
            return valor.getL().stream().map(EventoOperaciones::desenvolver).toList();
        }
        if (valor.getSS() != null) {
            return valor.getSS();
        }
        if (valor.getNS() != null) {
            return valor.getNS().stream().map(EventoOperaciones::numero).toList();
        }
        return null;
    }

    /**
     * DynamoDB guarda todo numero como texto.
     *
     * Se devuelve Long cuando es entero y Double cuando no: si todo saliera como
     * Double, un contador de interacciones llegaria al dashboard como "42.0" y
     * se mostraria asi en la tarjeta.
     */
    private static Object numero(String crudo) {
        try {
            if (crudo.contains(".") || crudo.contains("e") || crudo.contains("E")) {
                return Double.valueOf(crudo);
            }
            return Long.valueOf(crudo);
        } catch (NumberFormatException e) {
            return crudo;
        }
    }

}
