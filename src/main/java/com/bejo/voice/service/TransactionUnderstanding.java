package com.bejo.voice.service;

import java.util.List;

/**
 * Turns a recorded sentence about a sale into a structured proposal. A port: the real thing is
 * {@link GeminiTransactionUnderstanding}; tests plug in a scripted fake so nothing here needs the network or
 * Google credentials.
 *
 * <p>Everything in a {@link Proposal} is <b>untrusted</b>: it is what a language model made of audio that anyone can
 * speak into, so {@link VoiceTransactionService} validates and sanitises every field before it reaches the app.
 */
public interface TransactionUnderstanding {

    /**
     * @param pcm16Mono        16-bit little-endian mono PCM, no header
     * @param sampleRateHertz  the rate it was recorded at
     * @param catalogue        the merchant's products as the app sent them, already cleaned
     * @return what the model understood; never null
     * @throws com.bejo.shared.exception.ServiceUnavailableException when the model cannot be reached, is not
     *         configured, refuses, or answers with something that cannot be read — never a half-answer
     */
    Proposal understand(byte[] pcm16Mono, int sampleRateHertz, List<CatalogueEntry> catalogue);

    /** A product of the merchant's catalogue: id and price are the app's, the name is cleaned and capped. */
    record CatalogueEntry(long id, String name, Long price) {}

    /** The model's reading of the sentence. Any field may be null or nonsense; the service decides what survives. */
    record Proposal(String transcript, Long amount, String paymentMethod, String customerInfo, String merchantInfo,
                    List<ProposedItem> items) {}

    /** One product the model heard. {@code productId} is only a claim until it is found in the catalogue. */
    record ProposedItem(Long productId, String name, Long quantity, Long unitPrice) {}
}
