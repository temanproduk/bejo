package com.bejo.voice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One product line the speaker mentioned. A matched line carries the catalogue {@code productId}, the catalogue name and
 * the catalogue price; a new one ({@code isNew}) has no id, the name as spoken and, when a price was heard, that price.
 * Nulls are written out ({@code ALWAYS}) because this response is a contract with the app, which reads the fields by name
 * (the project default is to leave nulls out).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VoiceTransactionItemDto(
        Long productId,
        String name,
        int quantity,
        Long unitPrice,
        @JsonProperty("isNew") boolean isNew
) {}
