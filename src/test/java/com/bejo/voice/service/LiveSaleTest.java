package com.bejo.voice.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bejo.voice.dto.VoiceCatalogueProductDto;
import com.bejo.voice.dto.VoiceTransactionItemDto;
import com.bejo.voice.dto.VoiceTransactionResponseDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live "say the sale" session on its own: a hand-driven clock, a scripted model and a sink that writes down what the app
 * would have been told. The wire (WebSocket frames, filters) is covered by {@code VoiceLiveApiIntegrationTest}.
 */
class LiveSaleTest {

    private static final long MAX_AMOUNT = 999_999_999L;
    private static final int SECOND = 32_000;   // bytes of 16 kHz 16-bit mono PCM

    private static final List<VoiceCatalogueProductDto> CATALOGUE = List.of(
            new VoiceCatalogueProductDto(12L, "Kopi Susu", 18000L),
            new VoiceCatalogueProductDto(7L, "Roti Bakar", 15000L));

    /** What the app would have been told, in order. */
    private static final class RecordingSink implements LiveSale.Sink {
        final List<String> events = new ArrayList<>();
        final List<VoiceTransactionResponseDto> drafts = new ArrayList<>();
        VoiceTransactionResponseDto done;
        String errorCode;
        String errorMessage;

        @Override public void ready() { events.add("ready"); }
        @Override public void draft(VoiceTransactionResponseDto sale) { events.add("draft"); drafts.add(sale); }
        @Override public void done(VoiceTransactionResponseDto sale) { events.add("done"); done = sale; }
        @Override public void error(String code, String message) { events.add("error:" + code); errorCode = code; errorMessage = message; }
        @Override public void close() { events.add("close"); }

        VoiceTransactionResponseDto lastDraft() { return drafts.get(drafts.size() - 1); }
    }

    private final ManualTimers timers = new ManualTimers();
    private final ScriptedLiveUpstream model = new ScriptedLiveUpstream();
    private final RecordingSink sink = new RecordingSink();
    private final List<String> results = new ArrayList<>();
    private LiveSale.Settings settings = LiveSale.Settings.defaults();
    private VoiceTransactionService budget;

    @BeforeEach
    void setUp() {
        budget = budgetOf(6);
    }

    private static VoiceTransactionService budgetOf(int perMinute) {
        TransactionUnderstanding oneShot = (pcm, rate, catalogue) -> { throw new AssertionError("the live session never uses the one-shot model"); };
        return new VoiceTransactionService(oneShot, Clock.systemUTC(), 30, perMinute, 100, MAX_AMOUNT, new SimpleMeterRegistry());
    }

    private LiveSale newSale() {
        LiveSale sale = new LiveSale("alice", budget, model, sink, timers, settings, results::add);
        sale.arm();
        return sale;
    }

    /** Started, the model up, and a few seconds of talking recorded. */
    private LiveSale listening() {
        LiveSale sale = newSale();
        sale.start(CATALOGUE);
        model.ready();
        sale.audio(new byte[3 * SECOND]);
        return sale;
    }

    private static void said(ScriptedLiveUpstream model, String words, String function, String args) {
        model.phrase(words, function, args);
    }


    /** Loud audio, the way a voice reads to a loudness test: {@code seconds} of a 220 Hz tone. */
    private static byte[] speech(double seconds) {
        byte[] pcm = new byte[(int) (seconds * 16_000) * 2];
        for (int i = 0; i < pcm.length / 2; i++) {
            short sample = (short) Math.round(Math.sin(2 * Math.PI * 220 * i / 16_000.0) * 0.4 * 32767);
            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }

    /** Two phrases, 0.0-1.0 s and 2.3-3.3 s, with silence between: the way a merchant pauses between products. */
    private LiveSale twoPhrasesSaid() {
        LiveSale sale = newSale();
        sale.start(CATALOGUE);
        model.ready();
        sale.audio(speech(1));
        sale.audio(new byte[(int) (1.3 * SECOND)]);
        sale.audio(speech(1));
        return sale;
    }

    // ── starting ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void opensTheModelWithTheCleanedProductListAndSaysReadyOnlyOnce() {
        LiveSale sale = newSale();
        sale.start(List.of(new VoiceCatalogueProductDto(12L, "Kopi\nSusu|Es", 18000L)));

        assertThat(model.opened()).isEqualTo(1);
        assertThat(model.catalogue()).hasSize(1);
        assertThat(model.catalogue().get(0).name()).isEqualTo("Kopi Susu/Es");
        assertThat(sink.events).isEmpty();   // not ready until the model says so

        model.ready();
        model.ready();

        assertThat(sink.events).containsExactly("ready");
    }

    @Test
    void anAppThatNeverSaysWhatItWantsIsLetGo() {
        newSale();

        timers.advance(settings.startMillis() - 1);
        assertThat(sink.events).isEmpty();
        timers.advance(1);

        assertThat(sink.events).containsExactly("error:timeout", "close");
        assertThat(results).containsExactly("timeout");
        assertThat(timers.pending()).isZero();
    }

    @Test
    void aModelThatNeverComesUpIsAnErrorTheAppCanFallBackFrom() {
        LiveSale sale = newSale();
        sale.start(CATALOGUE);

        timers.advance(settings.readyMillis());

        assertThat(sink.events).containsExactly("error:unavailable", "close");
        assertThat(model.closed()).isTrue();
    }

    @Test
    void aModelThatCannotBeStartedIsUnavailable() {
        model.isUnavailable();
        LiveSale sale = newSale();
        sale.start(CATALOGUE);

        assertThat(sink.events).containsExactly("error:unavailable", "close");
        assertThat(sink.errorMessage).isEqualTo(GeminiTransactionUnderstanding.UNAVAILABLE);
        assertThat(results).containsExactly("unavailable");
    }

    @Test
    void aSessionIsOneRequestAgainstTheUsersBudget() {
        budget = budgetOf(1);
        LiveSale first = newSale();
        first.start(CATALOGUE);
        assertThat(model.opened()).isEqualTo(1);

        RecordingSink secondSink = new RecordingSink();
        LiveSale second = new LiveSale("alice", budget, model, secondSink, timers, settings, results::add);
        second.arm();
        second.start(CATALOGUE);

        assertThat(secondSink.events).containsExactly("error:rate_limited", "close");
        assertThat(model.opened()).isEqualTo(1);   // the second never reached the model
    }

    @Test
    void startingTwiceAudioBeforeStartAndOddAudioAreBadRequests() {
        LiveSale early = newSale();
        early.audio(new byte[3200]);
        assertThat(sink.events).containsExactly("error:bad_request", "close");

        RecordingSink again = new RecordingSink();
        LiveSale twice = new LiveSale("alice", budget, model, again, timers, settings, results::add);
        twice.arm();
        twice.start(CATALOGUE);
        twice.start(CATALOGUE);
        assertThat(again.events).containsExactly("error:bad_request", "close");

        RecordingSink odd = new RecordingSink();
        LiveSale oddSale = new LiveSale("alice", budget, model, odd, timers, settings, results::add);
        oddSale.arm();
        oddSale.start(CATALOGUE);
        oddSale.audio(new byte[3201]);
        assertThat(odd.events).containsExactly("error:bad_request", "close");
    }

    @Test
    void moreThanTwoHundredProductsIsABadRequest() {
        List<VoiceCatalogueProductDto> many = new ArrayList<>();
        for (long i = 1; i <= 201; i++) many.add(new VoiceCatalogueProductDto(i, "P" + i, 1000L));
        LiveSale sale = newSale();
        sale.start(many);

        assertThat(sink.events).containsExactly("error:bad_request", "close");
        assertThat(model.opened()).isZero();
    }

    // ── while the merchant is talking ───────────────────────────────────────────────────────────────────────

    @Test
    void aProductAppearsAsADraftWhileTheMerchantIsStillTalking() {
        listening();

        said(model, "dua kopi susu", "add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");

        assertThat(sink.events).contains("ready", "draft");
        assertThat(sink.done).isNull();   // still going
        assertThat(sink.lastDraft().understood()).isTrue();
        assertThat(sink.lastDraft().transcript()).isEqualTo("dua kopi susu");
        assertThat(sink.lastDraft().items()).containsExactly(new VoiceTransactionItemDto(12L, "Kopi Susu", 2, 18000L, false));
    }

    @Test
    void aMatchedProductIsPricedFromTheCatalogueNeverFromTheSpeech() {
        listening();

        model.call("add_item", "{\"name\":\"kopi\",\"quantity\":1,\"product_id\":12,\"unit_price\":1}");

        assertThat(sink.lastDraft().items().get(0).unitPrice()).isEqualTo(18000L);
        assertThat(sink.lastDraft().items().get(0).name()).isEqualTo("Kopi Susu");
    }

    @Test
    void anUnknownProductIsANewLineWithThePriceThatWasSaid() {
        listening();

        model.call("add_item", "{\"name\":\"Teh Botol\",\"quantity\":1,\"unit_price\":5000}");

        assertThat(sink.lastDraft().items()).containsExactly(new VoiceTransactionItemDto(null, "Teh Botol", 1, 5000L, true));
    }

    @Test
    void anIdTheAppNeverSentIsNotBelieved() {
        listening();

        model.call("add_item", "{\"name\":\"Kopi Hitam\",\"quantity\":1,\"product_id\":999,\"unit_price\":12000}");

        assertThat(sink.lastDraft().items()).containsExactly(new VoiceTransactionItemDto(null, "Kopi Hitam", 1, 12000L, true));
    }

    @Test
    void theSameProductSaidTwiceIsOneLineWithTheQuantitiesAdded() {
        listening();

        model.call("add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");
        model.call("add_item", "{\"name\":\"kopi susu\",\"quantity\":3,\"product_id\":12}");

        assertThat(sink.lastDraft().items()).containsExactly(new VoiceTransactionItemDto(12L, "Kopi Susu", 5, 18000L, false));
    }

    @Test
    void everyDraftIsTheWholeSaleSoFar() {
        listening();

        model.call("add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");
        model.call("add_item", "{\"name\":\"roti bakar\",\"quantity\":1,\"product_id\":7}");

        assertThat(sink.drafts).hasSize(2);
        assertThat(sink.drafts.get(0).items()).hasSize(1);
        assertThat(sink.drafts.get(1).items()).hasSize(2);
    }

    @Test
    void totalPaymentCustomerAndMerchantFillTheSaleAndAreCleaned() {
        listening();

        model.call("set_total", "{\"amount\":41000}");
        model.call("set_payment", "{\"method\":\"tunai\"}");
        model.call("set_customer", "{\"info\":\"  Budi\\n\"}");
        model.call("set_merchant", "{\"info\":\"Kasir 2\"}");

        var sale = sink.lastDraft();
        assertThat(sale.amount()).isEqualTo(41000L);
        assertThat(sale.paymentMethod()).isEqualTo("Tunai");
        assertThat(sale.customerInfo()).isEqualTo("Budi");
        assertThat(sale.merchantInfo()).isEqualTo("Kasir 2");
        assertThat(sale.understood()).isTrue();
    }

    @Test
    void aPaymentMethodOutsideTheSixAndAnAbsurdTotalAreDropped() {
        listening();

        model.call("set_payment", "{\"method\":\"bitcoin\"}");
        model.call("set_total", "{\"amount\":99999999999}");

        assertThat(sink.lastDraft().paymentMethod()).isNull();
        assertThat(sink.lastDraft().amount()).isNull();
    }

    @Test
    void aTotalCanBeCorrectedBySayingItAgain() {
        listening();

        model.call("set_total", "{\"amount\":40000}");
        model.call("set_total", "{\"amount\":45000}");

        assertThat(sink.lastDraft().amount()).isEqualTo(45000L);
    }

    @Test
    void nonsenseFromTheModelChangesNothing() {
        listening();
        int before = sink.drafts.size();

        model.call("add_item", "[]");
        model.call("add_item", "{\"quantity\":2}");               // no name: nothing to show and nothing to create
        model.call("launch_missiles", "{\"name\":\"x\"}");
        model.call("set_total", "{\"amount\":\"banyak\"}");
        model.call("set_payment", "{\"method\":42}");

        // every call produced a draft, but none of them holds anything
        assertThat(sink.drafts.size()).isGreaterThan(before);
        var sale = sink.lastDraft();
        assertThat(sale.items()).isEmpty();
        assertThat(sale.amount()).isNull();
        assertThat(sale.paymentMethod()).isNull();
        assertThat(sale.understood()).isFalse();
    }

    @Test
    void aSaleHoldsAtMostTwentyLines() {
        listening();

        for (int i = 0; i < 70; i++) model.call("add_item", "{\"name\":\"Barang " + i + "\",\"quantity\":1}");

        assertThat(sink.lastDraft().items()).hasSize(20);
    }

    // ── "Selesai" ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void endingWithNothingGoingOnHandsOverTheSaleAfterAShortQuiet() {
        LiveSale sale = listening();
        said(model, "dua kopi susu", "add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");

        sale.end();
        assertThat(model.audioEnded()).isTrue();
        timers.advance(settings.quietMillis() - 1);
        assertThat(sink.done).isNull();
        timers.advance(1);

        assertThat(sink.done).isNotNull();
        assertThat(sink.done.understood()).isTrue();
        assertThat(sink.done.items()).hasSize(1);
        assertThat(sink.events.subList(sink.events.size() - 2, sink.events.size())).containsExactly("done", "close");
        assertThat(model.closed()).isTrue();
        assertThat(results).containsExactly("understood");
        assertThat(timers.pending()).isZero();
    }

    @Test
    void endingSendsTheModelASecondOfSilenceSoItClosesTheLastPhrase() {
        LiveSale sale = listening();
        int before = model.audioBytes();

        sale.end();

        assertThat(model.audioBytes() - before).isEqualTo((int) (settings.endSilenceMillis() * 32));
    }

    @Test
    void aPhraseStillBeingAnsweredAtSelesaiIsWaitedFor() {
        LiveSale sale = listening();
        model.speech(true);   // the last words are still being heard when "Selesai" is tapped

        sale.end();
        timers.advance(3_000);
        assertThat(sink.done).isNull();   // the cap is 6 s; the model is still on it

        model.speech(false);
        model.call("add_item", "{\"name\":\"roti bakar\",\"quantity\":1,\"product_id\":7}");
        model.turnComplete();
        timers.advance(299);
        assertThat(sink.done).isNull();
        timers.advance(1);

        assertThat(sink.done.items()).containsExactly(new VoiceTransactionItemDto(7L, "Roti Bakar", 1, 15000L, false));
    }

    @Test
    void aPhraseTheModelNeverAnswersDoesNotHoldUpTheEnd() {
        LiveSale sale = listening();
        said(model, "dua kopi susu", "add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");
        model.speech(true);
        model.speech(false);   // heard, and the model lets it pass without a call or a "turn complete"

        sale.end();
        timers.advance(2_199);   // 1.5 s for the model to answer, then the 0.7 s quiet
        assertThat(sink.done).isNull();
        timers.advance(1);

        assertThat(sink.done).isNotNull();
        assertThat(sink.done.items()).hasSize(1);
    }

    @Test
    void theSettleTimeCapsTheWaitAndWhatTheModelHasIsTheAnswer() {
        LiveSale sale = listening();
        said(model, "dua kopi susu", "add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");
        model.speech(true);   // never closes

        sale.end();
        timers.advance(settings.settleMillis());

        assertThat(sink.done).isNotNull();
        assertThat(sink.done.items()).hasSize(1);
    }

    @Test
    void aTapIsNotASentenceAndIsAnsweredAtOnce() {
        LiveSale sale = newSale();
        sale.start(CATALOGUE);
        model.ready();
        sale.audio(new byte[SECOND / 4]);   // a quarter of a second

        sale.end();

        assertThat(sink.done).isNotNull();
        assertThat(sink.done.understood()).isFalse();
        assertThat(model.audioEnded()).isFalse();   // there was nothing to wait for
        assertThat(model.closed()).isTrue();
        assertThat(results).containsExactly("not_understood");
    }

    @Test
    void moreAudioThanAllowedEndsTheSaleAtTheLimit() {
        settings = new LiveSale.Settings(1, 40_000, 5_000, 8_000, 4_000, 700, 800);
        LiveSale sale = newSale();
        sale.start(CATALOGUE);
        model.ready();

        sale.audio(new byte[3 * SECOND]);   // three seconds, one allowed
        sale.audio(new byte[SECOND]);       // ignored: it is over

        assertThat(model.audioBytes()).isEqualTo(SECOND + (int) (settings.endSilenceMillis() * 32));
        assertThat(model.audioEnded()).isTrue();
    }

    @Test
    void theSessionEndsAtItsMaximumLengthWithWhatItHas() {
        listening();
        said(model, "dua kopi susu", "add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");

        timers.advance(settings.maxSessionMillis());

        assertThat(sink.done).isNotNull();
        assertThat(sink.done.items()).hasSize(1);
        assertThat(model.closed()).isTrue();
    }

    @Test
    void aModelStillBehindTheRecordingIsWaitedForEvenThoughNothingIsHappening() {
        LiveSale sale = twoPhrasesSaid();
        // the model has only got as far as the end of the first phrase (and its silence), as when audio arrived in a burst
        model.speech(true, 0.1);
        model.speech(false, 2.4);
        model.call("add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");
        model.turnComplete();

        sale.end();
        timers.advance(5_000);
        assertThat(sink.done).isNull();   // quiet, but the second phrase has not been heard yet

        model.speech(true, 2.6);
        model.speech(false, 3.9);
        model.call("add_item", "{\"name\":\"roti bakar\",\"quantity\":1,\"product_id\":7}");
        model.turnComplete();
        timers.advance(299);
        assertThat(sink.done).isNull();
        timers.advance(1);

        assertThat(sink.done.items()).hasSize(2);
    }

    @Test
    void aModelThatNeverCatchesUpIsGivenUntilTheCapAndThenWhatItHasIsTheAnswer() {
        LiveSale sale = twoPhrasesSaid();
        model.speech(true, 0.1);
        model.speech(false, 2.4);
        model.call("add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");
        model.turnComplete();

        sale.end();
        timers.advance(settings.settleMillis() - 1);
        assertThat(sink.done).isNull();
        timers.advance(1);

        assertThat(sink.done.items()).hasSize(1);
    }

    @Test
    void aModelThatHasHeardTheEndOfTheRecordingNeedsNoMoreWaitingThanTheQuiet() {
        LiveSale sale = twoPhrasesSaid();
        model.phrase("dua kopi susu", "add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");
        model.speech(true, 2.5);
        model.speech(false, 3.4);   // it has heard past the end of the last speech (3.3 s)
        model.call("add_item", "{\"name\":\"roti bakar\",\"quantity\":1,\"product_id\":7}");
        model.turnComplete();

        sale.end();
        timers.advance(settings.quietMillis());   // only the quiet, not the cap

        assertThat(sink.done.items()).hasSize(2);
    }

    @Test
    void tappingSelesaiBeforeTheModelIsUpWaitsForItAndStartsItsClockThen() {
        LiveSale sale = newSale();
        sale.start(CATALOGUE);
        sale.audio(speech(2));
        sale.end();

        timers.advance(5_000);
        assertThat(sink.events).isEmpty();   // not an error yet: the model has 8 s to come up
        model.ready();
        timers.advance(settings.settleMillis() - 1);
        assertThat(sink.done).isNull();
        timers.advance(1);

        assertThat(sink.done).isNotNull();   // the time to finish counted from when the model came up, not from the tap
        assertThat(model.audioEnded()).isTrue();
    }

    @Test
    void tappingSelesaiAndThenTheModelNeverComingUpIsAnError() {
        LiveSale sale = newSale();
        sale.start(CATALOGUE);
        sale.audio(speech(2));
        sale.end();

        timers.advance(settings.readyMillis());

        assertThat(sink.events).containsExactly("error:unavailable", "close");
    }

    // ── when something breaks ────────────────────────────────────────────────────────────────────────────────

    @Test
    void googleHangingUpBeforeSelesaiIsAnErrorTheAppCanFallBackFrom() {
        listening();

        model.closedByGoogle("closed 1011");

        assertThat(sink.events.subList(sink.events.size() - 2, sink.events.size())).containsExactly("error:upstream", "close");
        assertThat(results).containsExactly("upstream");
    }

    @Test
    void googleHangingUpAfterSelesaiStillHandsOverWhatWasUnderstood() {
        LiveSale sale = listening();
        said(model, "dua kopi susu", "add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");
        sale.end();

        model.closedByGoogle("closed 1000");

        assertThat(sink.done).isNotNull();
        assertThat(sink.done.items()).hasSize(1);
    }

    @Test
    void theAppLeavingStopsEverythingAndSaysNothing() {
        LiveSale sale = listening();
        int told = sink.events.size();

        sale.clientGone();

        assertThat(model.closed()).isTrue();
        assertThat(sink.events).hasSize(told);   // nobody left to tell, and the sink is not closed from here
        assertThat(results).containsExactly("aborted");
        assertThat(timers.pending()).isZero();
    }

    @Test
    void nothingIsSentAfterTheSessionIsOver() {
        LiveSale sale = listening();
        sale.end();
        timers.advance(settings.quietMillis());
        int told = sink.events.size();

        model.call("add_item", "{\"name\":\"kopi susu\",\"quantity\":2,\"product_id\":12}");
        model.heard("terlambat");
        model.closedByGoogle("late");
        sale.audio(new byte[3200]);
        sale.end();

        assertThat(sink.events).hasSize(told);
        assertThat(results).hasSize(1);
    }

    // ── what is kept ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theLogHoldsCountsAndTokensButNeverWhatWasSaid() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LiveSale.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            LiveSale sale = listening();
            model.usage(300, 20);
            model.usage(500, 10);
            said(model, "rahasia ibu Sari beli obat", "add_item", "{\"name\":\"obat rahasia\",\"quantity\":1}");
            model.call("set_customer", "{\"info\":\"Ibu Sari\"}");
            sale.end();
            timers.advance(settings.quietMillis());

            String log = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            assertThat(log).contains("user=alice").contains("tokens=800+30").contains("→ understood");
            assertThat(log).doesNotContainIgnoringCase("rahasia").doesNotContainIgnoringCase("sari").doesNotContainIgnoringCase("obat");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
