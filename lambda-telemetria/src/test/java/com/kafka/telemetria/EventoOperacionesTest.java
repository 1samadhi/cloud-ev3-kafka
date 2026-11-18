package com.kafka.telemetria;

import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la conversion de un item de DynamoDB al JSON del dashboard.
 *
 * Es la unica parte de las Lambdas con logica propia: el resto es llamar al
 * SDK. Un error aca no rompe nada visible en el despliegue, solo hace que el
 * tablero muestre datos raros, que es el peor tipo de falla.
 */
class EventoOperacionesTest {

    @Test
    @DisplayName("desenvuelve texto y conserva el campo tipo, que es el que enruta el evento")
    void desenvuelveTexto() {
        Map<String, Object> json = EventoOperaciones.aJson(Map.of(
                "tipo", new AttributeValue().withS("metricas"),
                "itemId", new AttributeValue().withS("item_116")));

        assertEquals("metricas", json.get("tipo"));
        assertEquals("item_116", json.get("itemId"));
    }

    /* DynamoDB guarda todo numero como texto. Si todo volviera como Double, un
       contador de interacciones llegaria al tablero como "42.0". */
    @Test
    @DisplayName("un numero entero vuelve como entero, no como decimal")
    void enterosSinDecimales() {
        Map<String, Object> json = EventoOperaciones.aJson(Map.of(
                "vistas", new AttributeValue().withN("42")));

        assertEquals(42L, json.get("vistas"));
    }

    @Test
    @DisplayName("un numero con parte decimal conserva los decimales")
    void decimales() {
        Map<String, Object> json = EventoOperaciones.aJson(Map.of(
                "score", new AttributeValue().withN("4.5")));

        assertEquals(4.5d, json.get("score"));
    }

    @Test
    @DisplayName("respeta los booleanos y traduce NULL a null")
    void booleanosYNulos() {
        Map<String, Object> json = EventoOperaciones.aJson(Map.of(
                "bloqueado", new AttributeValue().withBOOL(true),
                "bloqueadoHasta", new AttributeValue().withNULL(true)));

        assertEquals(true, json.get("bloqueado"));
        assertNull(json.get("bloqueadoHasta"));
    }

    /* La alerta de fraude y las metricas llevan listas de mapas: si el
       desenvolvimiento no fuera recursivo, el dashboard recibiria los tipos de
       DynamoDB crudos dentro del arreglo. */
    @Test
    @DisplayName("desenvuelve listas de mapas en profundidad")
    void listasAnidadas() {
        AttributeValue alerta = new AttributeValue().withM(Map.of(
                "idCliente", new AttributeValue().withS("ismael"),
                "cantidadIntentos", new AttributeValue().withN("3")));

        Map<String, Object> json = EventoOperaciones.aJson(Map.of(
                "tipo", new AttributeValue().withS("alerta"),
                "bloqueosActivos", new AttributeValue().withN("1"),
                "alertas", new AttributeValue().withL(List.of(alerta))));

        assertEquals("alerta", json.get("tipo"));
        assertEquals(1L, json.get("bloqueosActivos"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alertas = (List<Map<String, Object>>) json.get("alertas");
        assertEquals(1, alertas.size());
        assertEquals("ismael", alertas.get(0).get("idCliente"));
        assertEquals(3L, alertas.get(0).get("cantidadIntentos"));
    }

    @Test
    @DisplayName("un numero mal formado no tumba la conversion del lote completo")
    void numeroInvalidoNoRompe() {
        Map<String, Object> json = EventoOperaciones.aJson(Map.of(
                "raro", new AttributeValue().withN("no-es-un-numero")));

        assertEquals("no-es-un-numero", json.get("raro"));
    }

    @Test
    @DisplayName("mantiene el orden de las claves para que los logs sean legibles")
    void ordenEstable() {
        java.util.Map<String, AttributeValue> item = new java.util.LinkedHashMap<>();
        item.put("tipo", new AttributeValue().withS("metricas"));
        item.put("interaccionesPorMinuto", new AttributeValue().withN("12"));
        item.put("usuariosActivos", new AttributeValue().withN("3"));

        assertEquals(
                List.of("tipo", "interaccionesPorMinuto", "usuariosActivos"),
                List.copyOf(EventoOperaciones.aJson(item).keySet()));
    }

    @Test
    @DisplayName("un item vacio produce un mapa vacio, no una excepcion")
    void itemVacio() {
        assertTrue(EventoOperaciones.aJson(Map.of()).isEmpty());
    }
}
