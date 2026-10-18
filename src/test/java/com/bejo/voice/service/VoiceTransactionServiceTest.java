package com.bejo.voice.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bejo.shared.exception.ServiceUnavailableException;
import com.bejo.shared.exception.TooManyRequestsException;
import com.bejo.voice.dto.VoiceCatalogueProductDto;
import com.bejo.voice.dto.VoiceTransactionItemDto;
import com.bejo.voice.dto.VoiceTransactionRequestDto;
import com.bejo.voice.dto.VoiceTransactionResponseDto;
import com.bejo.voice.service.TransactionUnderstanding.CatalogueEntry;
import com.bejo.voice.service.TransactionUnderstanding.Proposal;
import com.bejo.voice.service.TransactionUnderstanding.ProposedItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceTransactionServiceTest {

    private static final int RATE = 16000;
    private static final long MAX_AMOUNT = 999_999_999L;

    /** Silence: seconds x 16000 samples x 2 bytes. */
    private static String clip(double seconds) {
        return Base64.getEncoder().encodeToString(new byte[(int) (seconds * RATE * 2)]);
    }

    private static final List<VoiceCatalogueProductDto> CATALOGUE = List.of(
            new VoiceCatalogueProductDto(12L, "Kopi Susu", 18000L),
            new VoiceCatalogueProductDto(7L, "Roti Bakar", 15000L));

    private Proposal heard = new Proposal(null, null, null, null, null, List.of());
    private RuntimeException failure;
    private int calls;
    private List<CatalogueEntry> seenCatalogue;
    private byte[] seenPcm;
    private int seenRate;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private VoiceTransactionService service;

    private static Clock clockAt(AtomicLong millis) {
        return new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        };
    }

    private VoiceTransactionService serviceWith(int maxSeconds, int perMinute, int perDay) {
        TransactionUnderstanding gemini = (pcm, rate, catalogue) -> {
            calls++;
            seenPcm = pcm;
            seenRate = rate;
            seenCatalogue = catalogue;
            if (failure != null) throw failure;
            return heard;
        };
        return new VoiceTransactionService(gemini, clockAt(now), maxSeconds, perMinute, perDay, MAX_AMOUNT, meters);
    }

    @BeforeEach
    void setUp() {
        service = serviceWith(30, 6, 100);
    }

    private VoiceTransactionResponseDto ask() {
        return service.understand("alice", new VoiceTransactionRequestDto(clip(2), RATE, CATALOGUE));
    }

    private static Proposal items(ProposedItem... items) {
        return new Proposal(null, null, null, null, null, List.of(items));
    }

    private static ProposedItem item(Long id, String name, Long qty, Long price) {
        return new ProposedItem(id, name, qty, price);
    }

    private double count(String result) {
        return meters.get("voice.transaction").tag("result", result).counter().count();
    }

    // ── a normal answer ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void passesWhatWasUnderstoodOnForTheAppToConfirm() {
        heard = new Proposal("jual dua kopi susu sama teh botol lima ribu, pelanggan Budi, tunai", 41000L, "Tunai",
                "Budi", "Kasir 2", List.of(item(12L, "kopi susu", 2L, 1L), item(null, "teh botol", 1L, 5000L)));

        var answer = ask();

        assertThat(answer.understood()).isTrue();
        assertThat(answer.transcript()).isEqualTo("jual dua kopi susu sama teh botol lima ribu, pelanggan Budi, tunai");
        assertThat(answer.amount()).isEqualTo(41000L);
        assertThat(answer.paymentMethod()).isEqualTo("Tunai");
        assertThat(answer.customerInfo()).isEqualTo("Budi");
        assertThat(answer.merchantInfo()).isEqualTo("Kasir 2");
        assertThat(answer.items()).containsExactly(
                new VoiceTransactionItemDto(12L, "Kopi Susu", 2, 18000L, false),
                new VoiceTransactionItemDto(null, "teh botol", 1, 5000L, true));
    }

    @Test
    void hearsTheClipAsSentAndDefaultsTheSampleRateTo16kHz() {
        service.understand("alice", new VoiceTransactionRequestDto(clip(2), null, null));

        assertThat(seenRate).isEqualTo(16000);
        assertThat(seenPcm).hasSize(64_000);
        assertThat(seenCatalogue).isEmpty();
    }

    @Test
    void aSentenceWithNoSaleIsNotUnderstoodAndEverythingIsEmpty() {
        var answer = ask();

        assertThat(answer.understood()).isFalse();
        assertThat(answer.amount()).isNull();
        assertThat(answer.items()).isEmpty();
        assertThat(answer.paymentMethod()).isNull();
    }

    // ── the product rules ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void anIdTheRequestDidNotContainBecomesANewProduct() {
        heard = items(item(999L, "Kopi Hitam", 1L, 12000L));

        var line = ask().items().getFirst();

        assertThat(line.productId()).isNull();
        assertThat(line.isNew()).isTrue();
        assertThat(line.name()).isEqualTo("Kopi Hitam");
        assertThat(line.unitPrice()).isEqualTo(12000L);
    }

    @Test
    void withNoCatalogueEveryIdIsUntrusted() {
        heard = items(item(12L, "Kopi Susu", 1L, null));

        var answer = service.understand("alice", new VoiceTransactionRequestDto(clip(2), RATE, null));

        assertThat(answer.items().getFirst().productId()).isNull();
        assertThat(answer.items().getFirst().isNew()).isTrue();
    }

    @Test
    void aMatchedProductIsPricedFromTheCatalogueNotFromWhatWasSaid() {
        heard = items(item(12L, "kopi susu murah banget", 1L, 1L));

        var line = ask().items().getFirst();

        assertThat(line.productId()).isEqualTo(12L);
        assertThat(line.isNew()).isFalse();
        assertThat(line.unitPrice()).isEqualTo(18000L);
        assertThat(line.name()).isEqualTo("Kopi Susu");   // the merchant's own name for it
    }

    @Test
    void aMatchedProductWithNoPriceInTheCatalogueHasNone() {
        heard = items(item(5L, "Gula", 1L, 9999L));

        var answer = service.understand("alice", new VoiceTransactionRequestDto(clip(2), RATE,
                List.of(new VoiceCatalogueProductDto(5L, "Gula", null))));

        assertThat(answer.items().getFirst().unitPrice()).isNull();
    }

    @Test
    void aNewProductKeepsTheHeardPriceOnlyWhenItIsReasonable() {
        heard = items(item(null, "A", 1L, 5000L), item(null, "B", 1L, null), item(null, "C", 1L, 0L),
                item(null, "D", 1L, -3L), item(null, "E", 1L, MAX_AMOUNT + 1), item(null, "F", 1L, MAX_AMOUNT));

        var prices = ask().items().stream().map(VoiceTransactionItemDto::unitPrice).toList();

        assertThat(prices).containsExactly(5000L, null, null, null, null, MAX_AMOUNT);
    }

    @Test
    void theSameProductSaidTwiceIsOneLineWithTheQuantitiesAdded() {
        heard = items(item(12L, "kopi", 2L, null), item(7L, "roti", 1L, null), item(12L, "kopi susu", 3L, null));

        var lines = ask().items();

        assertThat(lines).containsExactly(
                new VoiceTransactionItemDto(12L, "Kopi Susu", 5, 18000L, false),
                new VoiceTransactionItemDto(7L, "Roti Bakar", 1, 15000L, false));
    }

    @Test
    void mergedQuantitiesStayWithinTheCap() {
        heard = items(item(12L, "kopi", 9000L, null), item(12L, "kopi", 9000L, null));

        assertThat(ask().items().getFirst().quantity()).isEqualTo(9999);
    }

    @Test
    void aNewProductSaidTwiceStaysTwoLines() {
        heard = items(item(null, "Teh", 1L, 3000L), item(null, "Teh", 1L, 3000L));

        assertThat(ask().items()).hasSize(2);
    }

    @Test
    void moreThanTwentyItemsAreCutAtTwenty() {
        heard = items(IntStream.range(0, 30).mapToObj(i -> item(null, "Produk " + i, 1L, null)).toArray(ProposedItem[]::new));

        var lines = ask().items();

        assertThat(lines).hasSize(20);
        assertThat(lines.getLast().name()).isEqualTo("Produk 19");
    }

    @Test
    void duplicatesAreMergedBeforeTheCutSoTheyDoNotUseUpTheTwenty() {
        List<ProposedItem> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) many.add(item(12L, "kopi", 1L, null));        // one line
        for (int i = 0; i < 19; i++) many.add(item(null, "Produk " + i, 1L, null)); // 19 more
        heard = new Proposal(null, null, null, null, null, many);

        var lines = ask().items();

        assertThat(lines).hasSize(20);
        assertThat(lines.getFirst().quantity()).isEqualTo(15);
    }

    @Test
    void quantitiesAreClampedToOneThrough9999() {
        heard = items(item(null, "a", null, null), item(null, "b", 0L, null), item(null, "c", -4L, null),
                item(null, "d", 3L, null), item(null, "e", 9999L, null), item(null, "f", 10_000L, null),
                item(null, "g", Long.MAX_VALUE, null));

        var quantities = ask().items().stream().map(VoiceTransactionItemDto::quantity).toList();

        assertThat(quantities).containsExactly(1, 1, 1, 3, 9999, 9999, 9999);
    }

    @Test
    void anItemWithNoUsableNameAndNoKnownProductIsLeftOut() {
        heard = items(item(null, "  ", 1L, 5000L), item(null, null, 1L, null), item(404L, "\u0000​", 1L, null),
                item(null, "Teh", 1L, null));

        assertThat(ask().items()).extracting(VoiceTransactionItemDto::name).containsExactly("Teh");
    }

    @Test
    void nullEntriesAndAMissingListAreTolerated() {
        List<ProposedItem> withNull = new ArrayList<>();
        withNull.add(null);
        heard = new Proposal(null, 5000L, null, null, null, withNull);
        assertThat(ask().items()).isEmpty();

        heard = new Proposal(null, 5000L, null, null, null, null);
        var answer = ask();
        assertThat(answer.items()).isEmpty();
        assertThat(answer.understood()).isTrue();
    }

    // ── amount, payment, text ────────────────────────────────────────────────────────────────────────────────

    @Test
    void anAmountMustBeBetweenOneAndTheMaximum() {
        service = serviceWith(30, 1000, 1000);   // many asks in one test
        for (long ok : new long[]{1, 25_000, MAX_AMOUNT}) {
            heard = new Proposal(null, ok, null, null, null, List.of());
            assertThat(ask().amount()).isEqualTo(ok);
        }
        for (Long bad : new Long[]{null, 0L, -1L, MAX_AMOUNT + 1, Long.MAX_VALUE}) {
            heard = new Proposal(null, bad, null, null, null, List.of());
            assertThat(ask().amount()).as("amount %s", bad).isNull();
        }
    }

    @Test
    void aPaymentMethodOutsideTheSixBecomesNull() {
        service = serviceWith(30, 1000, 1000);   // many asks in one test
        for (String ok : List.of("Tunai", "Transfer", "Kartu Kredit", "Kartu Debit", "QRIS", "Lainnya")) {
            heard = new Proposal(null, null, ok, null, null, List.of());
            assertThat(ask().paymentMethod()).isEqualTo(ok);
        }
        for (String bad : new String[]{"Bitcoin", "Cash", "", "  ", "Tunai atau transfer", "Kartu", "QRIS; DROP TABLE"}) {
            heard = new Proposal(null, null, bad, null, null, List.of());
            assertThat(ask().paymentMethod()).as("payment %s", bad).isNull();
        }
        heard = new Proposal(null, null, null, null, null, List.of());
        assertThat(ask().paymentMethod()).isNull();
    }

    @Test
    void aPaymentMethodWrittenInAnotherCaseIsTheSameMethod() {
        heard = new Proposal(null, null, "  qris ", null, null, List.of());

        assertThat(ask().paymentMethod()).isEqualTo("QRIS");
    }

    @Test
    void textIsTrimmedAndCutToItsLimit() {
        heard = new Proposal("t".repeat(700), null, null, "  " + "c".repeat(80) + "  ", "m".repeat(51),
                List.of(item(null, "p".repeat(150), 1L, null)));

        var answer = ask();

        assertThat(answer.transcript()).hasSize(500);
        assertThat(answer.customerInfo()).isEqualTo("c".repeat(50));
        assertThat(answer.merchantInfo()).isEqualTo("m".repeat(50));
        assertThat(answer.items().getFirst().name()).isEqualTo("p".repeat(100));
    }

    @Test
    void textAtTheLimitIsKeptWhole() {
        heard = new Proposal("t".repeat(500), null, null, "c".repeat(50), "m".repeat(50), List.of());

        var answer = ask();

        assertThat(answer.transcript()).hasSize(500);
        assertThat(answer.customerInfo()).hasSize(50);
        assertThat(answer.merchantInfo()).hasSize(50);
    }

    @Test
    void controlCharactersAreRemovedAndLineBreaksBecomeSpaces() {
        heard = new Proposal("a\u0000b\u0007c", null, null, "Budi\nSantoso\t\r\n ", "‮Toko​ Maju‬", List.of());

        var answer = ask();

        assertThat(answer.transcript()).isEqualTo("abc");
        assertThat(answer.customerInfo()).isEqualTo("Budi Santoso");
        assertThat(answer.merchantInfo()).isEqualTo("Toko Maju");   // bidi override and zero-width space are gone
    }

    @Test
    void blankTextBecomesNull() {
        heard = new Proposal("   ", null, null, "\n\t", "\u0000", List.of());

        var answer = ask();

        assertThat(answer.transcript()).isNull();
        assertThat(answer.customerInfo()).isNull();
        assertThat(answer.merchantInfo()).isNull();
    }

    @Test
    void cuttingNeverSplitsACharacterOrLeavesATrailingSpace() {
        // 49 letters, a space, then an emoji (two UTF-16 units): the 50th character is the space, which must not dangle
        heard = new Proposal(null, null, null, "a".repeat(49) + " 😀x", "a".repeat(49) + "😀x", List.of());

        var answer = ask();

        assertThat(answer.customerInfo()).isEqualTo("a".repeat(49));
        assertThat(answer.merchantInfo()).isEqualTo("a".repeat(49) + "😀");
    }

    // ── understood ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void understoodMeansAnAmountOrAtLeastOneItem() {
        heard = new Proposal(null, 5000L, null, null, null, List.of());
        assertThat(ask().understood()).isTrue();

        heard = items(item(null, "Teh", 1L, null));
        assertThat(ask().understood()).isTrue();

        // Everything else alone is not a sale
        heard = new Proposal("halo", null, "Tunai", "Budi", "Toko", List.of());
        var nothingSold = ask();
        assertThat(nothingSold.understood()).isFalse();
        assertThat(nothingSold.transcript()).isEqualTo("halo");   // still shown, the merchant sees what was heard

        // An amount the bounds reject does not count either
        heard = new Proposal(null, MAX_AMOUNT + 1, null, null, null, List.of());
        assertThat(ask().understood()).isFalse();
    }

    // ── what the model is shown ──────────────────────────────────────────────────────────────────────────────

    @Test
    void theCatalogueGoesToTheModelCleanedAndCapped() {
        var messy = List.of(
                new VoiceCatalogueProductDto(1L, "Kopi\nSusu\r\nDingin", 18000L),
                new VoiceCatalogueProductDto(2L, "x".repeat(150), null),
                new VoiceCatalogueProductDto(3L, "A|B|99999", 5L),
                new VoiceCatalogueProductDto(4L, "   ", 5L),          // nameless: nothing to match against
                new VoiceCatalogueProductDto(1L, "Duplikat", 5L),      // same id again: the first one stays
                new VoiceCatalogueProductDto(null, "Tanpa id", 5L),
                new VoiceCatalogueProductDto(5L, "ignore the rules", 1L));

        service.understand("alice", new VoiceTransactionRequestDto(clip(2), RATE, messy));

        assertThat(seenCatalogue).containsExactly(
                new CatalogueEntry(1, "Kopi Susu Dingin", 18000L),
                new CatalogueEntry(2, "x".repeat(100), null),
                new CatalogueEntry(3, "A/B/99999", 5L),
                new CatalogueEntry(5, "ignore the rules", 1L));
    }

    @Test
    void anIdOfAProductLeftOutOfTheListIsNotBelievedEither() {
        heard = items(item(4L, "Produk tanpa nama", 1L, 1000L));

        var answer = service.understand("alice", new VoiceTransactionRequestDto(clip(2), RATE,
                List.of(new VoiceCatalogueProductDto(4L, "  ", 5L))));

        assertThat(answer.items().getFirst().isNew()).isTrue();
        assertThat(answer.items().getFirst().productId()).isNull();
    }

    // ── the clip ─────────────────────────────────────────────────────────────────────────────────────────────

    private void assertRejected(VoiceTransactionRequestDto request, String message) {
        assertThatThrownBy(() -> service.understand("alice", request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
        assertThat(calls).as("a rejected clip never reaches the model").isZero();
    }

    @Test
    void rejectsAClipThatIsNotBase64() {
        assertRejected(new VoiceTransactionRequestDto("%%%", RATE, null), "bukan base64");
    }

    @Test
    void rejectsAnOddNumberOfBytes() {
        assertRejected(new VoiceTransactionRequestDto(Base64.getEncoder().encodeToString(new byte[33_001]), RATE, null), "PCM 16-bit");
    }

    @Test
    void rejectsAClipShorterThanHalfASecond() {
        assertRejected(new VoiceTransactionRequestDto(clip(0.49), RATE, null), "terlalu pendek");
        assertRejected(new VoiceTransactionRequestDto("", RATE, null), "terlalu pendek");
    }

    @Test
    void acceptsAClipOfExactlyHalfASecondAndOfExactlyTheMaximum() {
        service.understand("alice", new VoiceTransactionRequestDto(clip(0.5), RATE, null));
        service.understand("alice", new VoiceTransactionRequestDto(clip(30), RATE, null));

        assertThat(calls).isEqualTo(2);
    }

    @Test
    void rejectsAClipLongerThanTheMaximum() {
        assertRejected(new VoiceTransactionRequestDto(clip(30.1), RATE, null), "terlalu panjang (maksimal 30 detik)");
    }

    @Test
    void theMaximumLengthIsConfigurable() {
        service = serviceWith(10, 6, 100);

        assertRejected(new VoiceTransactionRequestDto(clip(10.5), RATE, null), "maksimal 10 detik");
    }

    @Test
    void durationDependsOnTheSampleRate() {
        // The same bytes are twice as long at half the rate: 640 000 bytes are 20 s at 16 kHz but 40 s at 8 kHz
        assertRejected(new VoiceTransactionRequestDto(clip(20), 8000, null), "terlalu panjang");
    }

    @Test
    void rejectsASampleRateOutsideTheAllowedRange() {
        assertRejected(new VoiceTransactionRequestDto(clip(2), 7999, null), "sampleRateHertz");
        assertRejected(new VoiceTransactionRequestDto(clip(2), 48001, null), "sampleRateHertz");
        service.understand("alice", new VoiceTransactionRequestDto(clip(2), 8000, null));
        service.understand("alice", new VoiceTransactionRequestDto(clip(2), 48000, null));
    }

    @Test
    void rejectsMoreThan200Products() {
        List<VoiceCatalogueProductDto> tooMany = IntStream.rangeClosed(1, 201)
                .mapToObj(i -> new VoiceCatalogueProductDto((long) i, "P" + i, 1L)).toList();

        assertRejected(new VoiceTransactionRequestDto(clip(2), RATE, tooMany), "products maksimal 200");

        service.understand("alice", new VoiceTransactionRequestDto(clip(2), RATE, tooMany.subList(0, 200)));
        assertThat(seenCatalogue).hasSize(200);
    }

    @Test
    void rejectsAMissingAudio() {
        assertRejected(new VoiceTransactionRequestDto(null, RATE, null), "audio wajib diisi");
    }

    // ── the model failing ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aModelFailureIsPassedOnAsUnavailable() {
        failure = new ServiceUnavailableException("Input suara belum tersedia. Isi manual dulu ya.");

        assertThatThrownBy(this::ask).isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Input suara belum tersedia. Isi manual dulu ya.");
        assertThat(count("error")).isEqualTo(1);
    }

    @Test
    void anythingElseTheModelThrowsIsUnavailableToo() {
        failure = new IllegalStateException("boom, with details that must not reach the client");

        assertThatThrownBy(this::ask).isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Input suara belum tersedia. Isi manual dulu ya.");
        assertThat(count("error")).isEqualTo(1);
    }

    @Test
    void aMissingAnswerIsUnavailable() {
        heard = null;

        assertThatThrownBy(this::ask).isInstanceOf(ServiceUnavailableException.class);
    }

    // ── counters ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void countsUnderstoodNotUnderstoodAndError() {
        heard = new Proposal(null, 5000L, null, null, null, List.of());
        ask();
        ask();
        heard = new Proposal(null, null, null, null, null, List.of());
        ask();
        failure = new ServiceUnavailableException("x");
        try { ask(); } catch (ServiceUnavailableException expected) { /* counted */ }

        assertThat(count("understood")).isEqualTo(2);
        assertThat(count("not_understood")).isEqualTo(1);
        assertThat(count("error")).isEqualTo(1);
    }

    // ── limits ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aUserMayMakeSixRequestsInAMinuteAndNotASeventh() {
        for (int i = 0; i < 6; i++) ask();

        assertThatThrownBy(this::ask).isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("Terlalu sering");
        assertThat(calls).isEqualTo(6);
    }

    @Test
    void theMinuteWindowRolls() {
        for (int i = 0; i < 6; i++) { ask(); now.addAndGet(5_000); }   // 0, 5, 10, 15, 20, 25 s
        assertThatThrownBy(this::ask).isInstanceOf(TooManyRequestsException.class);   // at 30 s

        now.addAndGet(30_001);                                                        // 60.001 s after the first
        ask();                                                                        // the first has aged out
        assertThatThrownBy(this::ask).isInstanceOf(TooManyRequestsException.class);   // the second is still inside
    }

    @Test
    void afterAFullMinuteTheAllowanceIsBack() {
        for (int i = 0; i < 6; i++) ask();
        assertThatThrownBy(this::ask).isInstanceOf(TooManyRequestsException.class);

        now.addAndGet(60_001);

        for (int i = 0; i < 6; i++) ask();
        assertThat(calls).isEqualTo(12);
    }

    @Test
    void theAllowanceIsPerUser() {
        for (int i = 0; i < 6; i++) ask();

        service.understand("bob", new VoiceTransactionRequestDto(clip(2), RATE, CATALOGUE));

        assertThat(calls).isEqualTo(7);
    }

    @Test
    void aUserMayMake100RequestsADayAndNotAMore() {
        // Spread out so the minute limit never bites
        for (int i = 0; i < 100; i++) { ask(); now.addAndGet(61_000); }   // 100 x 61 s is well inside 24 h

        assertThatThrownBy(this::ask).isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("hari ini");
        assertThat(calls).isEqualTo(100);
    }

    @Test
    void theDayWindowRollsToo() {
        service = serviceWith(30, 100, 3);
        ask();
        now.addAndGet(1_000);
        ask();
        now.addAndGet(1_000);
        ask();
        assertThatThrownBy(this::ask).isInstanceOf(TooManyRequestsException.class).hasMessageContaining("hari ini");

        now.addAndGet(24 * 60 * 60 * 1000L - 2_000 + 1);   // 24 h and a millisecond after the first

        ask();                                              // the first has aged out
        assertThatThrownBy(this::ask).isInstanceOf(TooManyRequestsException.class);   // the second has not
    }

    @Test
    void aRequestTheLimiterRefusesIsNotRecordedAgainstTheUser() {
        service = serviceWith(30, 2, 100);
        ask();
        ask();
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(this::ask).isInstanceOf(TooManyRequestsException.class);
        }

        now.addAndGet(60_001);

        ask();   // refused attempts did not push the window forward
        ask();
        assertThat(calls).isEqualTo(4);
    }

    @Test
    void aCallThatReachedTheModelCountsEvenWhenTheModelFails() {
        service = serviceWith(30, 2, 100);
        failure = new ServiceUnavailableException("x");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(this::ask).isInstanceOf(ServiceUnavailableException.class);
        }

        assertThatThrownBy(this::ask).isInstanceOf(TooManyRequestsException.class);
        assertThat(calls).isEqualTo(2);
    }

    @Test
    void aRejectedClipDoesNotUseUpTheAllowance() {
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> service.understand("alice", new VoiceTransactionRequestDto("%%%", RATE, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        for (int i = 0; i < 6; i++) ask();
    }

    // ── privacy ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void neverLogsTheAudioWhatWasHeardOrTheProductList() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(VoiceTransactionService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            String audio = clip(2);
            heard = new Proposal("rahasia ibu Sari beli obat", 77_777L, "Tunai", "Ibu Sari", "Apotek Rahasia",
                    List.of(item(12L, "obat rahasia", 1L, null)));

            service.understand("alice", new VoiceTransactionRequestDto(audio, RATE,
                    List.of(new VoiceCatalogueProductDto(12L, "Kopi Susu", 18000L))));
            failure = new IllegalStateException("rahasia ibu Sari beli obat");
            assertThatThrownBy(this::ask).isInstanceOf(ServiceUnavailableException.class);

            String logged = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            assertThat(logged).isNotBlank().contains("user=alice").contains("bytes=64000").contains("items=1")
                    .doesNotContain("rahasia").doesNotContain("Sari").doesNotContain("77777").doesNotContain("Kopi Susu")
                    .doesNotContain(audio.substring(0, 20));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
