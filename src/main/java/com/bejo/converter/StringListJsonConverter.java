package com.bejo.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Persists a {@code List<String>} as a JSON array in a single TEXT column.
 * A null/empty list is stored as SQL NULL; an unreadable value degrades to an
 * empty list rather than failing the whole read.
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final Logger log = LoggerFactory.getLogger(StringListJsonConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to serialize string list, storing NULL: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return List.of();
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to parse stored string list, returning empty: {}", e.getMessage());
            return List.of();
        }
    }
}
