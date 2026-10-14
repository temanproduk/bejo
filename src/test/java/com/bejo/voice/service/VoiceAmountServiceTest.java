package com.bejo.voice.service;

import com.bejo.shared.exception.TooManyRequestsException;
import com.bejo.voice.dto.VoiceAmountRequestDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceAmountServiceTest {

    private static final int RATE = 16000;

    /** One second of silence: 16000 samples x 2 bytes. */
    private static String clip(double seconds) {
        return Base64.getEncoder().encodeToString(new byte[(int) (seconds * RATE * 2)]);
    }

    private Optional<SpeechToText.Transcript> heard = Optional.empty();
    private int calls;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private VoiceAmountService service;

    @BeforeEach
    void setUp() {
        SpeechToText stt = (pcm, rate) -> { calls++; return heard; };
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        service = new VoiceAmountService(stt, clock, 10, 3, 0.5, new SimpleMeterRegistry());
    }

    private void hears(String text) { heard = Optional.of(new SpeechToText.Transcript(text, 0.9)); }

    @Test
    void turnsWhatWasSaidIntoAnAmount() {
        hears("dua puluh lima ribu");

        var answer = service.recogniseAmount("alice", new VoiceAmountRequestDto(clip(2), RATE));

        assertThat(answer.amount()).isEqualTo(25_000L);
        assertThat(answer.transcript()).isEqualTo("dua puluh lima ribu");
        assertThat(answer.confidence()).isEqualTo(0.9);
    }

    @Test
    void sampleRateDefaultsTo16kHz() {
        hears("goceng");

        assertThat(service.recogniseAmount("alice", new VoiceAmountRequestDto(clip(2), null)).amount()).isEqualTo(5_000L);
    }

    @Test
    void aGuessTheRecogniserIsUnsureOfIsShownButNotOfferedAsAnAmount() {
        heard = Optional.of(new SpeechToText.Transcript("lima puluh ribu", 0.3));

        var answer = service.recogniseAmount("alice", new VoiceAmountRequestDto(clip(2), RATE));

        assertThat(answer.transcript()).isEqualTo("lima puluh ribu");   // the merchant sees what was heard
        assertThat(answer.amount()).isNull();                            // but no amount is made up from it
    }

    @Test
    void aConfidenceAtTheMinimumIsAccepted() {
        heard = Optional.of(new SpeechToText.Transcript("goceng", 0.5));

        assertThat(service.recogniseAmount("alice", new VoiceAmountRequestDto(clip(2), RATE)).amount()).isEqualTo(5_000L);
    }

    @Test
    void aMissingConfidenceIsNotTreatedAsLow() {
        // The recogniser sometimes sends none (the client then reports 0)
        heard = Optional.of(new SpeechToText.Transcript("goceng", 0.0));

        assertThat(service.recogniseAmount("alice", new VoiceAmountRequestDto(clip(2), RATE)).amount()).isEqualTo(5_000L);
    }

    @Test
    void speechWithoutANumberKeepsTheTranscriptButGivesNoAmount() {
        hears("halo apa kabar");

        var answer = service.recogniseAmount("alice", new VoiceAmountRequestDto(clip(2), RATE));

        assertThat(answer.amount()).isNull();
        assertThat(answer.transcript()).isEqualTo("halo apa kabar");
    }

    @Test
    void silenceGivesNeitherTranscriptNorAmount() {
        var answer = service.recogniseAmount("alice", new VoiceAmountRequestDto(clip(2), RATE));

        assertThat(answer.transcript()).isNull();
        assertThat(answer.amount()).isNull();
    }

    @Test
    void rejectsAClipThatIsNotBase64OrNotWholeSamples() {
        assertThatThrownBy(() -> service.recogniseAmount("alice", new VoiceAmountRequestDto("%%%not-base64%%%", RATE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> service.recogniseAmount("alice",
                new VoiceAmountRequestDto(Base64.getEncoder().encodeToString(new byte[33001]), RATE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("16-bit");
        assertThat(calls).isZero();   // never reached Google
    }

    @Test
    void rejectsAClipThatIsTooShortOrTooLongBeforeItCostsAnything() {
        assertThatThrownBy(() -> service.recogniseAmount("alice", new VoiceAmountRequestDto(clip(0.1), RATE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pendek");
        assertThatThrownBy(() -> service.recogniseAmount("alice", new VoiceAmountRequestDto(clip(11), RATE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10 detik");
        assertThat(calls).isZero();
    }

    @Test
    void limitsEachUserToAFewRequestsAMinute() {
        hears("goceng");
        var request = new VoiceAmountRequestDto(clip(1), RATE);
        for (int i = 0; i < 3; i++) service.recogniseAmount("alice", request);

        assertThatThrownBy(() -> service.recogniseAmount("alice", request)).isInstanceOf(TooManyRequestsException.class);
        assertThat(service.recogniseAmount("bob", request).amount()).isEqualTo(5_000L);   // others are not affected
        assertThat(calls).isEqualTo(4);

        now.addAndGet(61_000);   // a minute later alice may speak again
        assertThat(service.recogniseAmount("alice", request).amount()).isEqualTo(5_000L);
    }

    @Test
    void aRejectedClipDoesNotUseUpTheUsersAllowance() {
        hears("goceng");
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> service.recogniseAmount("alice", new VoiceAmountRequestDto(clip(0.1), RATE)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(service.recogniseAmount("alice", new VoiceAmountRequestDto(clip(1), RATE)).amount()).isEqualTo(5_000L);
    }
}
