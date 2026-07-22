package com.bejo.shared.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists an ordered {@code Map<String,String>} as a JSON object in a single TEXT
 * column (used for the monitored-apps {@code packageName -> issuer} map). A
 * null/empty map is stored as SQL NULL; an unreadable value degrades to an empty
 * map rather than failing the whole read. Insertion order is preserved.
 */
@Converter
public class StringMapJsonConverter implements AttributeConverter<Map<String, String>, String> {

    private static final Logger log = LoggerFactory.getLogger(StringMapJsonConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        if (attribute == null || attribute.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to serialize string map, storing NULL: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to parse stored string map, returning empty: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }
}
