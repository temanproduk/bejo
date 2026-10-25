package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.bejo.shared.exception.TooManyRequestsException;
import com.bejo.voice.dto.VoiceTransactionResponseDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Who may open a live session, and how many: one per user, a bounded number at once, and none when it is switched off. */
class VoiceLiveServiceTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ScriptedLiveUpstream model = new ScriptedLiveUpstream();

    private static final LiveSale.Sink SILENT = new LiveSale.Sink() {
        @Override public void ready() { }
        @Override public void draft(VoiceTransactionResponseDto sale) { }
        @Override public void done(VoiceTransactionResponseDto sale) { }
        @Override public void error(String code, String message) { }
        @Override public void close() { }
    };

    private VoiceLiveService service(boolean enabled, int maxSessions) {
        TransactionUnderstanding oneShot = (pcm, rate, catalogue) -> { throw new AssertionError(); };
        var transactions = new VoiceTransactionService(oneShot, Clock.systemUTC(), 30, 6, 100, 999_999_999L, meters);
        return new VoiceLiveService(transactions, model, meters, enabled, maxSessions, LiveSale.Settings.defaults());
    }

    @Test
    void aUserHasOneSessionAtATimeAndGetsAnotherOnceTheFirstIsOver() {
        VoiceLiveService live = service(true, 40);
        LiveSale first = live.begin("alice", SILENT);

        assertThatThrownBy(() -> live.begin("alice", SILENT)).isInstanceOf(TooManyRequestsException.class);
        live.begin("bob", SILENT);   // someone else is not affected
        assertThat(live.openSessions()).isEqualTo(2);

        first.clientGone();

        assertThat(live.openSessions()).isEqualTo(1);
        live.begin("alice", SILENT);
        assertThat(live.openSessions()).isEqualTo(2);
        live.stop();
    }

    @Test
    void anInstanceThatIsFullRefusesMore() {
        VoiceLiveService live = service(true, 2);
        live.begin("alice", SILENT);
        live.begin("bob", SILENT);

        assertThatThrownBy(() -> live.begin("carol", SILENT)).isInstanceOf(ServiceUnavailableException.class);
        live.stop();
    }

    @Test
    void switchedOffItRefusesEveryone() {
        VoiceLiveService live = service(false, 40);

        assertThatThrownBy(() -> live.begin("alice", SILENT)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(live.openSessions()).isZero();
        live.stop();
    }

    @Test
    void howSessionsEndIsCounted() {
        VoiceLiveService live = service(true, 40);
        live.begin("alice", SILENT).clientGone();

        assertThat(meters.get("voice.live").tag("result", "aborted").counter().count()).isEqualTo(1.0);
        live.stop();
    }

    @Test
    void theRealTimersFireAndAnAppThatNeverStartsIsLetGo() throws Exception {
        var errors = new java.util.concurrent.CopyOnWriteArrayList<String>();
        LiveSale.Sink sink = new LiveSale.Sink() {
            @Override public void ready() { }
            @Override public void draft(VoiceTransactionResponseDto sale) { }
            @Override public void done(VoiceTransactionResponseDto sale) { }
            @Override public void error(String code, String message) { errors.add(code); }
            @Override public void close() { }
        };
        TransactionUnderstanding oneShot = (pcm, rate, catalogue) -> { throw new AssertionError(); };
        var transactions = new VoiceTransactionService(oneShot, Clock.systemUTC(), 30, 6, 100, 999_999_999L, meters);
        var quick = new LiveSale.Settings(30, 40_000, 50, 8_000, 4_000, 700, 800);
        VoiceLiveService live = new VoiceLiveService(transactions, model, meters, true, 40, quick);

        live.begin("alice", sink);
        long deadline = System.currentTimeMillis() + 3_000;
        while (errors.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);

        assertThat(errors).containsExactly("timeout");
        assertThat(live.openSessions()).isZero();
        live.stop();
    }
}
