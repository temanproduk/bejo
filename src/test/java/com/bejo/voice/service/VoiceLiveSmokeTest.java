package com.bejo.voice.service;

import com.bejo.voice.dto.VoiceCatalogueProductDto;
import com.bejo.voice.dto.VoiceTransactionResponseDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live session against the REAL Gemini Live API on Vertex AI — the one thing no other test can say. Skipped unless
 * {@code LIVE_SMOKE_WAV} is set, so CI never calls Google:
 *
 * <pre>
 * LIVE_SMOKE_WAV=sale.wav            16 kHz mono 16-bit PCM WAV of someone saying a sale
 * LIVE_SMOKE_TOKEN=$(gcloud auth print-access-token)
 * LIVE_SMOKE_PROJECT=my-gcp-project
 * [LIVE_SMOKE_MODEL=gemini-3.8-live] [LIVE_SMOKE_LOCATION=us-central1] [LIVE_SMOKE_PACE=fast]
 * </pre>
 *
 * It streams the recording at the pace of a microphone (or flat out with {@code LIVE_SMOKE_PACE=fast}) through
 * {@link VoiceLiveService}, and prints when each draft and the final sale arrived relative to the audio. Billed like any Gemini
 * call (a few cents at most); the recording is sent to Google and nowhere else, and the token is never printed.
 */
@EnabledIfEnvironmentVariable(named = "LIVE_SMOKE_WAV", matches = ".+")
class VoiceLiveSmokeTest {

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void aWholeSaleThroughTheRealLiveApi() throws Exception {
        byte[] wav = Files.readAllBytes(Path.of(env("LIVE_SMOKE_WAV", "")));
        byte[] pcm = Arrays.copyOfRange(wav, 44, wav.length);   // a plain 44-byte header, as `afconvert -f WAVE -d LEI16@16000` writes
        String token = env("LIVE_SMOKE_TOKEN", "");
        boolean realtime = !"fast".equals(env("LIVE_SMOKE_PACE", "realtime"));

        var real = new GeminiLiveUpstream(HttpClient.newHttpClient(), env("LIVE_SMOKE_MODEL", "gemini-3.8-live"),
                env("LIVE_SMOKE_LOCATION", "us-central1"), env("LIVE_SMOKE_PROJECT", ""),
                () -> new GoogleSpeechToText.Auth(token, null), GeminiLiveUpstream::endpoint);
        long start = System.nanoTime();
        // the model's side of the conversation, with when each thing happened (names and positions only, never words)
        LiveTransactionUpstream upstream = (catalogue, listener) -> real.open(catalogue, new LiveTransactionUpstream.Listener() {
            private void trace(String what) { System.out.println(String.format("    model event +%5.2fs %s", (System.nanoTime() - start) / 1e9, what)); }
            @Override public void onReady() { listener.onReady(); }
            @Override public void onHeard(String text) { trace("heard"); listener.onHeard(text); }
            @Override public void onSpeech(boolean started, double seconds) { trace((started ? "speech START @" : "speech END @") + seconds); listener.onSpeech(started, seconds); }
            @Override public void onCall(String name, com.fasterxml.jackson.databind.JsonNode args) { trace("call " + name); listener.onCall(name, args); }
            @Override public void onTurnComplete() { trace("turn complete"); listener.onTurnComplete(); }
            @Override public void onUsage(long prompt, long response) { trace("usage " + prompt + "+" + response); listener.onUsage(prompt, response); }
            @Override public void onClosed(String reason) { trace("closed"); listener.onClosed(reason); }
        });
        TransactionUnderstanding oneShot = (p, r, c) -> { throw new AssertionError(); };
        var transactions = new VoiceTransactionService(oneShot, Clock.systemUTC(), 30, 6, 100, 999_999_999L, new SimpleMeterRegistry());
        var live = new VoiceLiveService(transactions, upstream, new SimpleMeterRegistry(), true, 5, LiveSale.Settings.defaults());

        long t0 = System.nanoTime();
        double[] audioSeconds = {0};
        CompletableFuture<VoiceTransactionResponseDto> done = new CompletableFuture<>();
        LiveSale sale = live.begin("smoke", new LiveSale.Sink() {
            private String at() { return String.format("[audio %5.1fs | wall %5.2fs]", audioSeconds[0], (System.nanoTime() - t0) / 1e9); }
            @Override public void ready() { System.out.println(at() + " READY"); }
            @Override public void draft(VoiceTransactionResponseDto s) { System.out.println(at() + " DRAFT " + describe(s)); }
            @Override public void done(VoiceTransactionResponseDto s) { System.out.println(at() + " DONE  " + describe(s)); done.complete(s); }
            @Override public void error(String code, String message) { System.out.println(at() + " ERROR " + code); done.completeExceptionally(new AssertionError(code)); }
            @Override public void close() { }
        });
        sale.start(List.of(new VoiceCatalogueProductDto(1L, "Kopi Susu", 18000L), new VoiceCatalogueProductDto(2L, "Teh Botol", 5000L),
                new VoiceCatalogueProductDto(3L, "Roti Bakar", 15000L)));

        int chunk = 3_200;   // 100 ms
        for (int i = 0; i < pcm.length; i += chunk) {
            sale.audio(Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + chunk)));
            audioSeconds[0] = Math.min(pcm.length, i + chunk) / 32_000.0;
            if (realtime) Thread.sleep(100);
        }
        System.out.println(String.format("[audio %5.1fs | wall %5.2fs] SELESAI", audioSeconds[0], (System.nanoTime() - t0) / 1e9));
        sale.end();

        VoiceTransactionResponseDto result = done.get(20, TimeUnit.SECONDS);
        assertThat(result.understood()).isTrue();
        assertThat(result.items()).isNotEmpty();
        live.stop();
    }

    private static String describe(VoiceTransactionResponseDto s) {
        StringBuilder text = new StringBuilder("items=[");
        s.items().forEach(i -> text.append(i.quantity()).append('x').append(i.name()).append(i.isNew() ? "(new)" : "").append(' '));
        return text.append("] payment=").append(s.paymentMethod()).append(" customer=").append(s.customerInfo())
                .append(" total=").append(s.amount()).toString();
    }
}
