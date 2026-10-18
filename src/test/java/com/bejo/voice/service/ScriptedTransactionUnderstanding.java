package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Test double for {@link TransactionUnderstanding}: "understands" whatever the test last told it to, so the functional
 * tests cover the whole voice-transaction endpoint without Google credentials or the network. Active only under the
 * {@code test} profile, replacing {@code GeminiTransactionUnderstanding} (which is {@code @Profile("!test")}).
 */
@Service
@Profile("test")
public class ScriptedTransactionUnderstanding implements TransactionUnderstanding {

    private static final Proposal NOTHING = new Proposal(null, null, null, null, null, List.of());

    private volatile Proposal next = NOTHING;
    private volatile boolean failing;
    private volatile int calls;
    private volatile byte[] lastPcm;
    private volatile int lastSampleRate;
    private volatile List<CatalogueEntry> lastCatalogue = List.of();

    /** Back to the default: hears nothing, works. Call it before each test, the bean is shared. */
    public void reset() {
        next = NOTHING;
        failing = false;
        calls = 0;
        lastPcm = null;
        lastCatalogue = List.of();
    }

    public void says(Proposal proposal) { next = proposal; }

    /** Answers as if Gemini had written this text; goes through the real answer parser. */
    public void saysText(String modelText) { next = GeminiTransactionUnderstanding.parseModelText(modelText); }

    public void isUnavailable() { failing = true; }

    public int calls() { return calls; }
    public byte[] lastPcm() { return lastPcm; }
    public int lastSampleRate() { return lastSampleRate; }
    public List<CatalogueEntry> lastCatalogue() { return lastCatalogue; }

    @Override
    public Proposal understand(byte[] pcm16Mono, int sampleRateHertz, List<CatalogueEntry> catalogue) {
        calls++;
        lastPcm = pcm16Mono;
        lastSampleRate = sampleRateHertz;
        lastCatalogue = catalogue;
        if (failing) throw new ServiceUnavailableException(GeminiTransactionUnderstanding.UNAVAILABLE);
        return next;
    }
}
