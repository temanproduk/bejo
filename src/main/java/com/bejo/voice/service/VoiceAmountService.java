package com.bejo.voice.service;

import com.bejo.shared.exception.TooManyRequestsException;
import com.bejo.voice.dto.VoiceAmountRequestDto;
import com.bejo.voice.dto.VoiceAmountResponseDto;
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
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Say the amount": a short voice clip in, the recognised words and the rupiah amount they come to out.
 *
 * <p>The clip is checked before it costs anything — well-formed base64 of whole 16-bit samples, between 0.3 s and
 * the configured maximum — and each user may make only a few requests a minute, because every call is billed by
 * Google. The audio is held in memory for the duration of the call and nothing else: it is not stored or logged,
 * and neither is the transcript (only its length and the amount), since a merchant may say more than a number.
 */
@Service
public class VoiceAmountService {

    private static final Logger log = LoggerFactory.getLogger(VoiceAmountService.class);

    /** Shorter than this is a tap, not speech. */
    private static final double MIN_SECONDS = 0.3;

    private final SpeechToText speechToText;
    private final Clock clock;
    private final int maxSeconds;
    private final int maxPerMinute;
    private final double minConfidence;
    private final Counter recognised;
    private final Counter lowConfidence;
    private final Counter noAmount;
    private final Counter nothingHeard;

    // Request times per username, newest last, only the last minute kept
    private final ConcurrentHashMap<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    @Autowired
    public VoiceAmountService(SpeechToText speechToText,
                              @Value("${app.voice.max-seconds:10}") int maxSeconds,
                              @Value("${app.voice.max-requests-per-minute:12}") int maxPerMinute,
                              @Value("${app.voice.min-confidence:0.5}") double minConfidence,
                              MeterRegistry meterRegistry) {
        this(speechToText, Clock.systemUTC(), maxSeconds, maxPerMinute, minConfidence, meterRegistry);
    }

    VoiceAmountService(SpeechToText speechToText, Clock clock, int maxSeconds, int maxPerMinute,
                       double minConfidence, MeterRegistry meterRegistry) {
        this.speechToText = speechToText;
        this.clock = clock;
        this.maxSeconds = maxSeconds;
        this.maxPerMinute = maxPerMinute;
        this.minConfidence = minConfidence;
        this.recognised = Counter.builder("voice.amount").tag("result", "recognised").register(meterRegistry);
        this.noAmount = Counter.builder("voice.amount").tag("result", "no_amount").register(meterRegistry);
        this.lowConfidence = Counter.builder("voice.amount").tag("result", "low_confidence").register(meterRegistry);
        this.nothingHeard = Counter.builder("voice.amount").tag("result", "nothing_heard").register(meterRegistry);
    }

    public VoiceAmountResponseDto recogniseAmount(String username, VoiceAmountRequestDto request) {
        int sampleRate = request.sampleRateHertz() != null ? request.sampleRateHertz() : 16000;
        byte[] pcm = decode(request.audio(), sampleRate);
        claimSlot(username);

        var heard = speechToText.transcribe(pcm, sampleRate);
        if (heard.isEmpty()) {
            nothingHeard.increment();
            log.info("[VOICE] user={} bytes={} → nothing heard", username, pcm.length);
            return new VoiceAmountResponseDto(null, null, null);
        }
        // The recogniser can be wrong, most of all in a noisy room, and a wrong amount that looks right is worse than
        // none: below the minimum confidence the words are still shown (the merchant sees what was heard) but no
        // amount is offered. A confidence of 0 means the recogniser did not report one, which is not the same as low.
        double confidence = heard.get().confidence();
        boolean confident = confidence <= 0 || confidence >= minConfidence;
        OptionalLong amount = confident ? IndonesianAmountParser.parse(heard.get().text()) : OptionalLong.empty();
        (!confident ? lowConfidence : amount.isPresent() ? recognised : noAmount).increment();
        log.info("[VOICE] user={} bytes={} chars={} confidence={} → amount={}", username, pcm.length,
                heard.get().text().length(), String.format("%.2f", heard.get().confidence()),
                amount.isPresent() ? amount.getAsLong() : "none");
        return new VoiceAmountResponseDto(heard.get().text(), amount.isPresent() ? amount.getAsLong() : null,
                heard.get().confidence());
    }

    private byte[] decode(String audio, int sampleRate) {
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

    /** One request per call, at most {@code maxPerMinute} in any rolling minute per user. */
    private void claimSlot(String username) {
        long now = clock.millis();
        Deque<Long> times = recent.computeIfAbsent(username, u -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) times.pollFirst();
            if (times.size() >= maxPerMinute) {
                throw new TooManyRequestsException("Terlalu sering memakai input suara. Coba lagi sebentar lagi.");
            }
            times.addLast(now);
        }
        if (recent.size() > 10_000) recent.entrySet().removeIf(e -> {
            synchronized (e.getValue()) { return e.getValue().isEmpty() || now - e.getValue().peekLast() > 60_000; }
        });
    }
}
