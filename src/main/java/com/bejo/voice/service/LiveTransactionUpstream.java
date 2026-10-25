package com.bejo.voice.service;

import com.bejo.voice.service.TransactionUnderstanding.CatalogueEntry;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The model end of "say the sale" while the merchant is still talking: a streaming session in which audio goes in as it is
 * recorded and the model answers with function calls ("add 2 kopi susu") as soon as it has heard them. A port: the real thing is
 * {@link GeminiLiveUpstream}; tests plug in a scripted fake so nothing needs the network or Google credentials.
 *
 * <p>Everything the model sends is <b>untrusted</b>, exactly like a {@link TransactionUnderstanding.Proposal}: {@link LiveSale}
 * runs it through {@link SaleValidator} before the app sees it.
 */
public interface LiveTransactionUpstream {

    /**
     * Starts a session for one sale. Returns at once; the connection is made in the background and {@link Listener#onReady}
     * says when the model can take audio. Whatever goes wrong afterwards is reported as {@link Listener#onClosed}.
     *
     * @param catalogue the merchant's products as the app sent them, already cleaned
     * @throws com.bejo.shared.exception.ServiceUnavailableException when it cannot even be started (not configured, no credentials)
     */
    Session open(List<CatalogueEntry> catalogue, Listener listener);

    interface Session {
        /** 16 kHz mono 16-bit little-endian PCM. Never blocks; fine to call before {@link Listener#onReady} (it is held until then). */
        void sendAudio(byte[] pcm);

        /** No more audio will follow: lets the model finish what it has heard. */
        void endAudio();

        /** Drops the session. Safe to call twice. */
        void close();
    }

    /** Called from the upstream's own threads, in the order the model said things. */
    interface Listener {
        void onReady();

        /** A piece of what the model heard, as text. */
        void onHeard(String text);

        /**
         * The model's own voice detection: someone started ({@code true}) or stopped ({@code false}) talking. {@code audioSeconds} is
         * how far into the audio it had got when it decided so (NaN when it does not say) — which is how a burst of audio that the
         * model has not worked through yet is told from a model that has finished.
         */
        void onSpeech(boolean started, double audioSeconds);

        /** The model asked for a function: {@code add_item}, {@code set_total}, {@code set_payment}, {@code set_customer} or {@code set_merchant}. */
        void onCall(String name, JsonNode args);

        /** The model is done reacting to what it has heard so far. */
        void onTurnComplete();

        /** What the model charged for, added up by the caller. */
        void onUsage(long promptTokens, long responseTokens);

        /** The session is over, from either side; {@code reason} is for the log (never what was said). */
        void onClosed(String reason);
    }
}
