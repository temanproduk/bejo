package com.bejo.voice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The other frames of the live "say the sale" WebSocket: {@code ready} (the model can take audio) and {@code error}
 * (the session cannot go on — {@code code} is one of {@code bad_request}, {@code rate_limited}, {@code unavailable},
 * {@code upstream}, {@code timeout}, and {@code message} is words the app may show; it should fall back to the one-shot endpoint).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VoiceLiveEventDto(String type, String code, String message) {

    public static VoiceLiveEventDto ready() {
        return new VoiceLiveEventDto("ready", null, null);
    }

    public static VoiceLiveEventDto error(String code, String message) {
        return new VoiceLiveEventDto("error", code, message);
    }
}
