package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.bejo.shared.exception.TooManyRequestsException;
import com.bejo.voice.dto.VoiceCatalogueProductDto;
import com.bejo.voice.dto.VoiceTransactionRequestDto;
import com.bejo.voice.dto.VoiceTransactionResponseDto;
import com.bejo.voice.service.TransactionUnderstanding.CatalogueEntry;
import com.bejo.voice.service.TransactionUnderstanding.Proposal;
import com.bejo.voice.service.TransactionUnderstanding.ProposedItem;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * One merchant saying one sale out loud, <b>while they are still saying it</b>: the live counterpart of
 * {@link VoiceTransactionService#understand}. The app opens a session, streams the microphone into {@link #audio}, and gets a
 * {@link Sink#draft} each time the model has understood another piece ("2 kopi susu" appears a second after it is said);
 * {@link #end} (the "Selesai" tap) lets the model finish, and {@link Sink#done} carries the final sale, in exactly the shape the
 * one-shot endpoint answers with.
 *
 * <p>A session is a small state machine ({@link Phase}), driven from three directions at once — the app's frames, the model's
 * events and the timers — so every method is {@code synchronized}. What the model asks for is untrusted and goes through
 * {@link SaleValidator} on every draft, never once at the end: the app is shown nothing the one-shot endpoint would not have
 * allowed. The draft is always the <i>whole</i> sale so far (not a delta), so a lost or repeated model call can never leave the
 * app out of step.
 *
 * <p>Nothing here is stored or logged but the user, byte and item counts and token totals — not the audio, not the words.
 */
public final class LiveSale {

    private static final Logger log = LoggerFactory.getLogger(LiveSale.class);

    /** Where the app is told what is going on. Implemented by the WebSocket handler; calls may come from any thread. */
    public interface Sink {
        /** The model can take audio. */
        void ready();

        /** The sale as understood so far. */
        void draft(VoiceTransactionResponseDto sale);

        /** The final sale. Followed by {@link #close}. */
        void done(VoiceTransactionResponseDto sale);

        /** The session cannot go on; the app should fall back to the one-shot endpoint. Followed by {@link #close}. */
        void error(String code, String message);

        void close();
    }

    /** Told once, when the session is over, how it ended: {@code understood}, {@code not_understood}, {@code aborted} or an error code. */
    interface Observer {
        void finished(String result);
    }

    /** The clock of the session: a task to run after a delay. Real timers in production, steps in tests. */
    interface Timers {
        Cancel after(long millis, Runnable task);

        interface Cancel {
            void cancel();
        }
    }

    /**
     * @param maxAudioSeconds  the sale is as long as this; more audio ends it
     * @param maxSessionMillis the whole session, from connecting. Must stay below the platform's request timeout (Cloud Run, 45 s)
     * @param startMillis      the app must say what it wants within this
     * @param readyMillis      the model must be ready within this after that
     * @param settleMillis     after "Selesai" (and once the model is up), the model has at most this long to finish what it is on
     * @param quietMillis      after "Selesai", nothing happening for this long means the sale is complete
     * @param endSilenceMillis silence appended after the last word, so the model's voice detection closes the final phrase
     */
    record Settings(int maxAudioSeconds, long maxSessionMillis, long startMillis, long readyMillis, long settleMillis,
                    long quietMillis, long endSilenceMillis) {
        static Settings defaults() {
            return new Settings(30, 40_000, 5_000, 8_000, 6_000, 700, 800);
        }
    }

    private enum Phase { AWAITING_START, CONNECTING, LISTENING, SETTLING, FINISHED }

    static final String CODE_BAD_REQUEST = "bad_request";
    static final String CODE_RATE_LIMITED = "rate_limited";
    static final String CODE_UNAVAILABLE = "unavailable";
    static final String CODE_UPSTREAM = "upstream";
    static final String CODE_TIMEOUT = "timeout";

    private static final int BYTES_PER_SECOND = 16_000 * 2;
    /** Shorter than this is a tap, not a sentence (as in the one-shot endpoint). */
    private static final int MIN_AUDIO_BYTES = BYTES_PER_SECOND / 2;
    /** Once events have been seen since "Selesai", the model is nearly done: this much quiet is enough. */
    private static final long QUIET_AFTER_EVENTS_MS = 300;
    /** How far short of where the last speech ends the model's own position may be and still count as having heard it. */
    private static final double CATCH_UP_TOLERANCE_SECONDS = 0.3;
    /** After a phrase ends the model answers it, or decides not to: if it has not said anything in this long, it is not going to. */
    private static final long THINK_MS = 1_500;
    private static final int MAX_CALLED_ITEMS = 60;
    private static final int MAX_HEARD_CHARS = 1_000;

    private final String username;
    private final SaleValidator validator;
    private final VoiceTransactionService budget;
    private final LiveTransactionUpstream upstream;
    private final Sink sink;
    private final Timers timers;
    private final Settings settings;
    private final Observer observer;

    private Phase phase = Phase.AWAITING_START;
    private Map<Long, CatalogueEntry> catalogue = Map.of();
    private LiveTransactionUpstream.Session session;
    private boolean ready;
    private long audioBytes;
    private final long maxAudioBytes;

    // What the model has called for so far (untrusted until a draft is made)
    private final List<ProposedItem> items = new ArrayList<>();
    private Long total;
    private String payment;
    private String customer;
    private String merchant;
    private final StringBuilder heard = new StringBuilder();

    // How far the model is: is someone talking, is it still answering the phrase that just ended, has anything happened since "Selesai"
    private boolean speechOpen;
    private boolean thinking;
    private boolean eventSinceEnd;
    /** Where the last speech we sent ends, and how far into the audio the model last said it had got (-1: it has not said). */
    private final SpeechEnd speechEnd = new SpeechEnd();
    private double modelHeardUntil = -1;
    private long promptTokens;
    private long responseTokens;

    private Timers.Cancel startTimer;
    private Timers.Cancel readyTimer;
    private Timers.Cancel sessionTimer;
    private Timers.Cancel settleTimer;
    private Timers.Cancel quietTimer;
    private Timers.Cancel thinkTimer;

    LiveSale(String username, VoiceTransactionService budget, LiveTransactionUpstream upstream, Sink sink, Timers timers,
             Settings settings, Observer observer) {
        this.username = username;
        this.validator = budget.validator();
        this.budget = budget;
        this.upstream = upstream;
        this.sink = sink;
        this.timers = timers;
        this.settings = settings;
        this.observer = observer;
        this.maxAudioBytes = (long) settings.maxAudioSeconds() * BYTES_PER_SECOND;
    }

    /** Starts the clocks. Separate from the constructor so the timers never see a half-built session. */
    synchronized void arm() {
        startTimer = timers.after(settings.startMillis(), this::startTimedOut);
        sessionTimer = timers.after(settings.maxSessionMillis(), this::sessionTimedOut);
    }

    // ── from the app ─────────────────────────────────────────────────────────────────────────────────────────

    /** The app's first message: its product list. Opens the model session and takes one place in the user's request budget. */
    public synchronized void start(List<VoiceCatalogueProductDto> products) {
        if (phase != Phase.AWAITING_START) {
            fail(CODE_BAD_REQUEST, "Sesi suara sudah dimulai");
            return;
        }
        cancel(startTimer);
        List<VoiceCatalogueProductDto> sent = products == null ? List.of() : products;
        if (sent.size() > VoiceTransactionRequestDto.MAX_PRODUCTS) {
            fail(CODE_BAD_REQUEST, "products maksimal " + VoiceTransactionRequestDto.MAX_PRODUCTS + " produk");
            return;
        }
        catalogue = SaleValidator.catalogue(sent);
        // Connecting before the model is opened: its "ready" may arrive before open() has returned
        phase = Phase.CONNECTING;
        readyTimer = timers.after(settings.readyMillis(), this::readyTimedOut);
        try {
            budget.claimSlot(username);
            session = upstream.open(List.copyOf(catalogue.values()), new FromModel());
        } catch (TooManyRequestsException e) {
            fail(CODE_RATE_LIMITED, e.getMessage());
        } catch (ServiceUnavailableException e) {
            fail(CODE_UNAVAILABLE, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[VOICE] live user={} could not open a session: {}", username, e.getClass().getSimpleName());
            fail(CODE_UNAVAILABLE, GeminiTransactionUnderstanding.UNAVAILABLE);
        }
    }

    /** A piece of the recording: 16 kHz mono 16-bit little-endian PCM, any length. */
    public synchronized void audio(byte[] pcm) {
        if (phase == Phase.AWAITING_START) {
            fail(CODE_BAD_REQUEST, "Kirim pesan start dulu");
            return;
        }
        if (phase == Phase.SETTLING || phase == Phase.FINISHED || pcm == null || pcm.length == 0) return;
        if (pcm.length % 2 != 0) {
            fail(CODE_BAD_REQUEST, "audio harus PCM 16-bit");
            return;
        }
        long room = maxAudioBytes - audioBytes;
        byte[] chunk = pcm.length <= room ? pcm : Arrays.copyOf(pcm, (int) (room & ~1L));
        if (chunk.length > 0) {
            audioBytes += chunk.length;
            speechEnd.add(chunk);
            session.sendAudio(chunk);
        }
        if (pcm.length > room) end();   // the sale is as long as it may be
    }

    /** "Selesai": no more audio. The final sale follows as soon as the model has finished with what it heard. */
    public synchronized void end() {
        if (phase == Phase.AWAITING_START) {
            conclude("aborted");
            return;
        }
        if (phase != Phase.CONNECTING && phase != Phase.LISTENING) return;
        if (audioBytes < MIN_AUDIO_BYTES) {   // a tap, not a sentence
            conclude("not_understood");
            sink.done(validator.validated(new Proposal(null, null, null, null, null, List.of()), catalogue));
            sink.close();
            return;
        }
        phase = Phase.SETTLING;
        session.sendAudio(new byte[(int) (settings.endSilenceMillis() * BYTES_PER_SECOND / 1000) & ~1]);
        session.endAudio();
        if (ready) armSettle();
        reevaluate();
    }

    /** The web layer could not make sense of a frame (not JSON, an unknown type, an invalid product list). */
    public synchronized void reject(String message) {
        fail(CODE_BAD_REQUEST, message);
    }

    /** The app's connection is gone (closed, dropped, or errored): nobody is left to tell. */
    public synchronized void clientGone() {
        conclude("aborted");
    }

    // ── from the model ───────────────────────────────────────────────────────────────────────────────────────

    private final class FromModel implements LiveTransactionUpstream.Listener {

        @Override
        public void onReady() {
            synchronized (LiveSale.this) {
                if (phase == Phase.FINISHED || ready) return;
                ready = true;
                cancel(readyTimer);
                if (phase == Phase.CONNECTING) phase = Phase.LISTENING;
                else if (phase == Phase.SETTLING) armSettle();
                sink.ready();
                reevaluate();
            }
        }

        @Override
        public void onHeard(String text) {
            synchronized (LiveSale.this) {
                if (phase == Phase.FINISHED || text == null || text.isBlank()) return;
                if (heard.length() < MAX_HEARD_CHARS) {
                    if (!heard.isEmpty()) heard.append(' ');
                    heard.append(text.trim());
                }
                changed();
            }
        }

        @Override
        public void onSpeech(boolean started, double audioSeconds) {
            synchronized (LiveSale.this) {
                if (phase == Phase.FINISHED) return;
                speechOpen = started;
                if (!started) {
                    // the model will now answer that phrase — or, as it sometimes does, let it pass without a word
                    thinking = true;
                    cancel(thinkTimer);
                    thinkTimer = timers.after(THINK_MS, LiveSale.this::stoppedThinking);
                    if (!Double.isNaN(audioSeconds)) modelHeardUntil = Math.max(modelHeardUntil, audioSeconds);
                }
                touched();
            }
        }

        @Override
        public void onCall(String name, JsonNode args) {
            synchronized (LiveSale.this) {
                if (phase == Phase.FINISHED) return;
                apply(name, args);
                changed();
            }
        }

        @Override
        public void onTurnComplete() {
            synchronized (LiveSale.this) {
                if (phase == Phase.FINISHED) return;
                thinking = false;
                cancel(thinkTimer);
                touched();
            }
        }

        @Override
        public void onUsage(long prompt, long response) {
            synchronized (LiveSale.this) {
                promptTokens += prompt;
                responseTokens += response;
            }
        }

        @Override
        public void onClosed(String reason) {
            synchronized (LiveSale.this) {
                if (phase == Phase.FINISHED) return;
                log.warn("[VOICE] live user={} upstream closed ({})", username, reason);
                // After "Selesai" the model had what it was going to get: finish with that. Before it, the app has its own
                // recording and should send it the one-shot way.
                if (phase == Phase.SETTLING && ready) finishWithDone();
                else fail(CODE_UPSTREAM, GeminiTransactionUnderstanding.UNAVAILABLE);
            }
        }
    }

    /** Whatever the model asked for, as a change to the sale. The names are the functions declared in {@link TransactionPrompt}. */
    private void apply(String name, JsonNode args) {
        if (name == null || args == null || !args.isObject()) return;
        switch (name) {
            case "add_item" -> {
                if (items.size() < MAX_CALLED_ITEMS) {
                    items.add(new ProposedItem(GeminiTransactionUnderstanding.whole(args.get("product_id")),
                            GeminiTransactionUnderstanding.text(args.get("name")),
                            GeminiTransactionUnderstanding.whole(args.get("quantity")),
                            GeminiTransactionUnderstanding.whole(args.get("unit_price"))));
                }
            }
            case "set_total" -> total = GeminiTransactionUnderstanding.whole(args.get("amount"));
            case "set_payment" -> payment = GeminiTransactionUnderstanding.text(args.get("method"));
            case "set_customer" -> customer = GeminiTransactionUnderstanding.text(args.get("info"));
            case "set_merchant" -> merchant = GeminiTransactionUnderstanding.text(args.get("info"));
            default -> log.debug("[VOICE] live user={} model asked for an unknown function", username);
        }
    }

    // ── finishing ────────────────────────────────────────────────────────────────────────────────────────────

    /** The sale as it stands, validated. */
    private VoiceTransactionResponseDto snapshot() {
        return validator.validated(new Proposal(heard.isEmpty() ? null : heard.toString(), total, payment, customer, merchant,
                List.copyOf(items)), catalogue);
    }

    /** Something about the sale changed: tell the app, and see whether the sale is complete. */
    private void changed() {
        sink.draft(snapshot());
        touched();
    }

    private void touched() {
        if (phase == Phase.SETTLING) eventSinceEnd = true;
        reevaluate();
    }

    /**
     * After "Selesai": complete when the model has stopped hearing speech, has heard the end of the recording, is not in the middle of
     * answering a phrase, and then stayed quiet for a moment. Anything it does in that moment starts the wait again; {@link Settings#settleMillis} is the cap.
     */
    private void reevaluate() {
        cancel(quietTimer);
        if (phase != Phase.SETTLING || !ready || speechOpen || thinking || modelBehind()) return;
        long wait = eventSinceEnd ? Math.min(QUIET_AFTER_EVENTS_MS, settings.quietMillis()) : settings.quietMillis();
        quietTimer = timers.after(wait, this::settled);
    }

    /**
     * The recording ends in speech that the model has not yet reported hearing the end of. Quiet means nothing then: audio that
     * arrived in a burst (the connection took a moment to come up) keeps the model busy long after the last frame was sent.
     */
    private boolean modelBehind() {
        double lastSpeech = speechEnd.endSeconds();
        return lastSpeech >= 0 && modelHeardUntil + CATCH_UP_TOLERANCE_SECONDS < lastSpeech;
    }

    private void armSettle() {
        if (settleTimer == null) settleTimer = timers.after(settings.settleMillis(), this::settleTimedOut);
    }

    private synchronized void stoppedThinking() {
        if (phase == Phase.FINISHED) return;
        thinking = false;
        reevaluate();
    }

    private synchronized void settled() {
        if (phase == Phase.SETTLING) finishWithDone();
    }

    private synchronized void startTimedOut() {
        if (phase == Phase.AWAITING_START) fail(CODE_TIMEOUT, "Sesi suara tidak dimulai");
    }

    /** The model did not come up in time: the app has its own recording and can send it the one-shot way. */
    private synchronized void readyTimedOut() {
        if (!ready && (phase == Phase.CONNECTING || phase == Phase.SETTLING)) fail(CODE_UNAVAILABLE, GeminiTransactionUnderstanding.UNAVAILABLE);
    }

    /** The longest a session may last. What the model has understood by now is the answer. */
    private synchronized void sessionTimedOut() {
        if (phase == Phase.AWAITING_START || phase == Phase.CONNECTING) fail(CODE_TIMEOUT, "Sesi suara berakhir");
        else if (phase != Phase.FINISHED) finishOrFail();
    }

    /** After "Selesai" the model had its time: what it has understood is the answer. */
    private synchronized void settleTimedOut() {
        if (phase == Phase.SETTLING) finishOrFail();
    }

    /** A session whose model never came up has nothing to answer with; that is a failure the app can retry the one-shot way. */
    private void finishOrFail() {
        if (ready) finishWithDone();
        else fail(CODE_UNAVAILABLE, GeminiTransactionUnderstanding.UNAVAILABLE);
    }

    private void finishWithDone() {
        VoiceTransactionResponseDto answer = snapshot();
        conclude(answer.understood() ? "understood" : "not_understood");
        sink.done(answer);
        sink.close();
    }

    private void fail(String code, String message) {
        if (phase == Phase.FINISHED) return;
        conclude(code);
        sink.error(code, message);
        sink.close();
    }

    /**
     * The session is over: stop the clocks, let go of the model and of the user's place, and note how it ended. All of this comes
     * <i>before</i> the app is told anything, so an app that reconnects the moment it hears "done" or "error" is never turned
     * away because the session it just finished is still counted as open.
     */
    private void conclude(String result) {
        if (phase == Phase.FINISHED) return;
        phase = Phase.FINISHED;
        for (Timers.Cancel timer : new Timers.Cancel[]{startTimer, readyTimer, sessionTimer, settleTimer, quietTimer, thinkTimer}) cancel(timer);
        if (session != null) session.close();
        log.info("[VOICE] live user={} audioBytes={} items={} tokens={}+{} → {}", username, audioBytes, items.size(),
                promptTokens, responseTokens, result);
        observer.finished(result);
    }

    private static void cancel(Timers.Cancel timer) {
        if (timer != null) timer.cancel();
    }
}
