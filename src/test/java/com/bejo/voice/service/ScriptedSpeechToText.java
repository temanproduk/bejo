package com.bejo.voice.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Test double for {@link SpeechToText}: "hears" whatever the test last told it to, so the functional tests cover the
 * whole voice endpoint without Google credentials or the network. Active only under the {@code test} profile,
 * replacing {@code GoogleSpeechToText} (which is {@code @Profile("!test")}).
 */
@Service
@Profile("test")
public class ScriptedSpeechToText implements SpeechToText {

    private volatile Optional<Transcript> next = Optional.empty();
    private volatile int calls;
    private volatile byte[] lastAudio;
    private volatile int lastSampleRate;

    public void hears(String text) { hears(text, 0.92); }
    public void hears(String text, double confidence) { next = Optional.of(new Transcript(text, confidence)); }
    public void hearsNothing() { next = Optional.empty(); }
    public int calls() { return calls; }
    public byte[] lastAudio() { return lastAudio; }
    public int lastSampleRate() { return lastSampleRate; }

    @Override
    public Optional<Transcript> transcribe(byte[] pcm16Mono, int sampleRateHertz) {
        calls++;
        lastAudio = pcm16Mono;
        lastSampleRate = sampleRateHertz;
        return next;
    }
}
