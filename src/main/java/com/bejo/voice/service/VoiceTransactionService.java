package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.bejo.shared.exception.TooManyRequestsException;
import com.bejo.voice.dto.VoiceCatalogueProductDto;
import com.bejo.voice.dto.VoiceTransactionRequestDto;
import com.bejo.voice.dto.VoiceTransactionResponseDto;
import com.bejo.voice.service.TransactionUnderstanding.CatalogueEntry;
import com.bejo.voice.service.TransactionUnderstanding.Proposal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Say the sale": a recording of a shop owner describing a sale, in; the amount, payment method, customer, merchant
 * and products they said, out, for the app to show on a confirmation screen before anything is saved.
 *
 * <p>The understanding is Gemini's ({@link TransactionUnderstanding}); this class and {@link SaleValidator} are what make it
 * safe to use. A language model listening to audio that anyone can speak into is an untrusted source, so everything it
 * returns is validated field by field (see {@link SaleValidator} for the rules). The model can be wrong, but it cannot make
 * the app save something the merchant's own data would not allow. The live, word-by-word variant of this ({@link LiveSale})
 * runs on the same validator and draws on the same per-user request budget.
 *
 * <p>The clip is checked before it costs anything, and each user may make only a few requests a minute and a day,
 * because every call is billed. The audio is held in memory for the duration of the call and nothing else: it is
 * not stored or logged, and neither is what was understood (only the user, the byte count and the item count).
 */
@Service
public class VoiceTransactionService {

    private static final Logger log = LoggerFactory.getLogger(VoiceTransactionService.class);

    /** Shorter than this is a tap, not a sentence. */
    private static final double MIN_SECONDS = 0.5;
    private static final long MINUTE_MS = 60_000L;
    private static final long DAY_MS = 24 * 60 * MINUTE_MS;

    private final TransactionUnderstanding understanding;
    private final Clock clock;
    private final int maxSeconds;
    private final int maxPerMinute;
    private final int maxPerDay;
    private final SaleValidator validator;
    private final Counter understood;
    private final Counter notUnderstood;
    private final Counter failed;

    /** One user's request times, newest last: the last minute in one list and the last day in the other. */
    private static final class Requests {
        final Deque<Long> minute = new ArrayDeque<>();
        final Deque<Long> day = new ArrayDeque<>();
    }

    private final ConcurrentHashMap<String, Requests> recent = new ConcurrentHashMap<>();

    @Autowired
    public VoiceTransactionService(TransactionUnderstanding understanding,
                                   @Value("${app.voice.transaction.max-seconds:30}") int maxSeconds,
                                   @Value("${app.voice.transaction.max-requests-per-minute:6}") int maxPerMinute,
                                   @Value("${app.voice.transaction.max-requests-per-day:100}") int maxPerDay,
                                   @Value("${app.voice.transaction.max-amount:999999999}") long maxAmount,
                                   MeterRegistry meterRegistry) {
        this(understanding, Clock.systemUTC(), maxSeconds, maxPerMinute, maxPerDay, maxAmount, meterRegistry);
    }

    VoiceTransactionService(TransactionUnderstanding understanding, Clock clock, int maxSeconds, int maxPerMinute,
                            int maxPerDay, long maxAmount, MeterRegistry meterRegistry) {
        this.understanding = understanding;
        this.clock = clock;
        this.maxSeconds = maxSeconds;
        this.maxPerMinute = maxPerMinute;
        this.maxPerDay = maxPerDay;
        this.validator = new SaleValidator(maxAmount);
        this.understood = Counter.builder("voice.transaction").tag("result", "understood").register(meterRegistry);
        this.notUnderstood = Counter.builder("voice.transaction").tag("result", "not_understood").register(meterRegistry);
        this.failed = Counter.builder("voice.transaction").tag("result", "error").register(meterRegistry);
    }

    public VoiceTransactionResponseDto understand(String username, VoiceTransactionRequestDto request) {
        int sampleRate = request.sampleRateHertz() != null ? request.sampleRateHertz() : 16000;
        if (sampleRate < 8000 || sampleRate > 48000) {
            throw new IllegalArgumentException("sampleRateHertz harus antara 8000 dan 48000");
        }
        List<VoiceCatalogueProductDto> sent = request.products() != null ? request.products() : List.of();
        if (sent.size() > VoiceTransactionRequestDto.MAX_PRODUCTS) {
            throw new IllegalArgumentException("products maksimal " + VoiceTransactionRequestDto.MAX_PRODUCTS + " produk");
        }
        byte[] pcm = decode(request.audio(), sampleRate);
        Map<Long, CatalogueEntry> catalogue = SaleValidator.catalogue(sent);
        claimSlot(username);

        Proposal heard;
        try {
            heard = understanding.understand(pcm, sampleRate, List.copyOf(catalogue.values()));
            if (heard == null) throw new ServiceUnavailableException(GeminiTransactionUnderstanding.UNAVAILABLE);
        } catch (ServiceUnavailableException e) {
            failed.increment();
            log.warn("[VOICE] transaction user={} bytes={} → unavailable", username, pcm.length);
            throw e;
        } catch (RuntimeException e) {
            failed.increment();
            log.error("[VOICE] transaction user={} bytes={} → failed: {}", username, pcm.length, e.getClass().getSimpleName());
            throw new ServiceUnavailableException(GeminiTransactionUnderstanding.UNAVAILABLE);
        }

        VoiceTransactionResponseDto answer = validator.validated(heard, catalogue);
        (answer.understood() ? understood : notUnderstood).increment();
        log.info("[VOICE] transaction user={} bytes={} items={} → {}", username, pcm.length, answer.items().size(),
                answer.understood() ? "understood" : "not understood");
        return answer;
    }

    // ── the request ──────────────────────────────────────────────────────────────────────────────────────────

    private byte[] decode(String audio, int sampleRate) {
        if (audio == null) throw new IllegalArgumentException("audio wajib diisi");
        byte[] pcm;
        try {
            pcm = Base64.getDecoder().decode(audio);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("audio bukan base64 yang valid");
        }
        if (pcm.length % 2 != 0) throw new IllegalArgumentException("audio harus PCM 16-bit");
        double seconds = pcm.length / 2.0 / sampleRate;
        if (seconds < MIN_SECONDS) throw new IllegalArgumentException("Suara terlalu pendek");
        if (seconds > maxSeconds) throw new IllegalArgumentException("Suara terlalu panjang (maksimal " + maxSeconds + " detik)");
        return pcm;
    }

    /**
     * One request per call, at most {@code maxPerMinute} in any rolling minute and {@code maxPerDay} in any rolling
     * 24 hours per user. A call that reaches the model counts even when the model then fails: otherwise a clip that
     * makes it answer badly could be repeated without limit, and every such call is billed.
     */
    void claimSlot(String username) {
        long now = clock.millis();
        Requests requests = recent.computeIfAbsent(username, u -> new Requests());
        synchronized (requests) {
            forgetOlderThan(requests.minute, now - MINUTE_MS);
            forgetOlderThan(requests.day, now - DAY_MS);
            if (requests.minute.size() >= maxPerMinute) {
                throw new TooManyRequestsException("Terlalu sering memakai input suara. Coba lagi sebentar lagi.");
            }
            if (requests.day.size() >= maxPerDay) {
                throw new TooManyRequestsException("Batas input suara hari ini sudah tercapai. Isi manual dulu ya, atau coba lagi besok.");
            }
            requests.minute.addLast(now);
            requests.day.addLast(now);
        }
        if (recent.size() > 10_000) recent.entrySet().removeIf(e -> {
            synchronized (e.getValue()) { return !e.getValue().day.isEmpty() && now - e.getValue().day.peekLast() > DAY_MS; }
        });
    }

    SaleValidator validator() {
        return validator;
    }

    private static void forgetOlderThan(Deque<Long> times, long cutoff) {
        while (!times.isEmpty() && times.peekFirst() < cutoff) times.pollFirst();
    }
}
