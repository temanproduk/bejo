package com.bejo.voice.service;

import java.util.Optional;

/**
 * Turns a short clip of speech into text. A port: the real thing is {@link GoogleSpeechToText}; tests plug in a fake
 * so nothing here needs the network or Google credentials.
 */
public interface SpeechToText {

    /** What was heard, or empty when the clip held no recognisable speech. */
    Optional<Transcript> transcribe(byte[] pcm16Mono, int sampleRateHertz);

    record Transcript(String text, double confidence) {}
}
