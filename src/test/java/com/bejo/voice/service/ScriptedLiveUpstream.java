package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Test double for {@link LiveTransactionUpstream}: records what the app sent the model and lets the test play the model's side
 * ("it heard this", "it asks for add_item with these arguments"), so the whole live endpoint is covered without Google
 * credentials or the network. Active only under the {@code test} profile, replacing {@code GeminiLiveUpstream}
 * (which is {@code @Profile("!test")}).
 */
@Service
@Profile("test")
public class ScriptedLiveUpstream implements LiveTransactionUpstream {

    private static final ObjectMapper JSON = new ObjectMapper();

    private volatile Listener listener;
    private volatile boolean unavailable;
    private volatile boolean readyAtOnce;
    private volatile int opened;
    private volatile boolean audioEnded;
    private volatile boolean closed;
    private volatile List<TransactionUnderstanding.CatalogueEntry> catalogue = List.of();
    private final List<byte[]> audio = new ArrayList<>();

    /** Back to the default: works, says nothing, waits for the test to call {@link #ready}. Call it before each test, the bean is shared. */
    public synchronized void reset() {
        listener = null;
        unavailable = false;
        readyAtOnce = false;
        opened = 0;
        audioEnded = false;
        closed = false;
        catalogue = List.of();
        audio.clear();
    }

    public void isUnavailable() { unavailable = true; }

    /** The model comes up as soon as the session opens, instead of when the test says so. */
    public void readyAtOnce() { readyAtOnce = true; }

    // ── what the app did ──
    public int opened() { return opened; }
    public boolean audioEnded() { return audioEnded; }
    public boolean closed() { return closed; }
    public List<TransactionUnderstanding.CatalogueEntry> catalogue() { return catalogue; }
    public synchronized int audioBytes() { return audio.stream().mapToInt(a -> a.length).sum(); }
    public synchronized int audioFrames() { return audio.size(); }

    // ── the model's side ──
    public void ready() { listener.onReady(); }
    public void heard(String text) { listener.onHeard(text); }
    public void speech(boolean started) { listener.onSpeech(started, Double.NaN); }
    /** The model's voice detection, with how far into the audio it had got. */
    public void speech(boolean started, double audioSeconds) { listener.onSpeech(started, audioSeconds); }
    public void turnComplete() { listener.onTurnComplete(); }
    public void usage(long prompt, long response) { listener.onUsage(prompt, response); }
    public void closedByGoogle(String reason) { listener.onClosed(reason); }

    public void call(String name, String argsJson) {
        try {
            listener.onCall(name, JSON.readTree(argsJson));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** One whole phrase, the way the model does it: speech starts, the words, speech stops, the call, the turn ends. */
    public void phrase(String words, String function, String argsJson) {
        speech(true);
        heard(words);
        speech(false);
        call(function, argsJson);
        turnComplete();
    }

    @Override
    public Session open(List<TransactionUnderstanding.CatalogueEntry> catalogue, Listener listener) {
        if (unavailable) throw new ServiceUnavailableException(GeminiTransactionUnderstanding.UNAVAILABLE);
        this.listener = listener;
        this.catalogue = catalogue;
        this.opened++;
        if (readyAtOnce) listener.onReady();
        return new Session() {
            @Override public void sendAudio(byte[] pcm) { synchronized (ScriptedLiveUpstream.this) { audio.add(pcm); } }
            @Override public void endAudio() { audioEnded = true; }
            @Override public void close() { closed = true; }
        };
    }
}
