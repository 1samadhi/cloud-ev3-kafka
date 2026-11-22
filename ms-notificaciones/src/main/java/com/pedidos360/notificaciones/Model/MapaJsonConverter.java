package com.pedidos360.notificaciones.Model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Map;

/**
 * Guarda el mapa "datos" de la notificación como JSON en una columna de texto.
 * Su forma la decide el productor y cambia por tipo: una tabla clave/valor
 * aparte no aporta nada para algo que sólo se lee de vuelta entero.
 */
@Converter
public class MapaJsonConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> TIPO = new TypeReference<>() { };

    @Override
    public String convertToDatabaseColumn(Map<String, Object> mapa) {
        if (mapa == null || mapa.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(mapa);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("No se pudo serializar 'datos' a JSON", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, TIPO);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Columna 'datos' con JSON inválido", e);
        }
    }
}
