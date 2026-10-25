package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.bejo.shared.exception.TooManyRequestsException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Opens the live "say the sale" sessions ({@link LiveSale}): one at a time per user, a bounded number at once on this instance
 * (each holds a connection to Google), and each one counted as a single request against the user's minute and day budget
 * ({@link VoiceTransactionService#claimSlot}) because that is what it costs. Owns the timers the sessions run on.
 */
@Service
public class VoiceLiveService {

    private static final Logger log = LoggerFactory.getLogger(VoiceLiveService.class);

    private final VoiceTransactionService transactions;
    private final LiveTransactionUpstream upstream;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final int maxSessions;
    private final LiveSale.Settings settings;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread t = new Thread(runnable, "voice-live-timer");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public VoiceLiveService(VoiceTransactionService transactions, LiveTransactionUpstream upstream, MeterRegistry meters,
                            @Value("${app.voice.live.enabled:true}") boolean enabled,
                            @Value("${app.voice.live.max-sessions:40}") int maxSessions,
                            @Value("${app.voice.live.max-audio-seconds:30}") int maxAudioSeconds,
                            @Value("${app.voice.live.max-session-seconds:40}") int maxSessionSeconds,
                            @Value("${app.voice.live.start-timeout-seconds:5}") int startTimeoutSeconds,
                            @Value("${app.voice.live.ready-timeout-seconds:8}") int readyTimeoutSeconds,
                            @Value("${app.voice.live.settle-seconds:6}") int settleSeconds,
                            @Value("${app.voice.live.quiet-millis:700}") long quietMillis,
                            @Value("${app.voice.live.end-silence-millis:800}") long endSilenceMillis) {
        this(transactions, upstream, meters, enabled, maxSessions,
                new LiveSale.Settings(maxAudioSeconds, maxSessionSeconds * 1000L, startTimeoutSeconds * 1000L,
                        readyTimeoutSeconds * 1000L, settleSeconds * 1000L, quietMillis, endSilenceMillis));
    }

    VoiceLiveService(VoiceTransactionService transactions, LiveTransactionUpstream upstream, MeterRegistry meters,
                     boolean enabled, int maxSessions, LiveSale.Settings settings) {
        this.transactions = transactions;
        this.upstream = upstream;
        this.meters = meters;
        this.enabled = enabled;
        this.maxSessions = maxSessions;
        this.settings = settings;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    /**
     * A new session for {@code username}, reporting to {@code sink}. The session is armed, but takes no place in the request budget
     * until the app sends its product list ({@link LiveSale#start}).
     *
     * @throws ServiceUnavailableException when live input is switched off or this instance is full
     * @throws TooManyRequestsException    when the user already has a session going
     */
    public LiveSale begin(String username, LiveSale.Sink sink) {
        if (!enabled) throw new ServiceUnavailableException(GeminiTransactionUnderstanding.UNAVAILABLE);
        if (active.size() >= maxSessions) {
            log.warn("[VOICE] live: {} sessions are open, refusing user={}", active.size(), username);
            throw new ServiceUnavailableException(GeminiTransactionUnderstanding.UNAVAILABLE);
        }
        if (!active.add(username)) throw new TooManyRequestsException("Masih ada sesi suara yang berjalan.");
        LiveSale sale;
        try {
            sale = new LiveSale(username, transactions, upstream, sink, this::after, settings, result -> {
                active.remove(username);
                meters.counter("voice.live", "result", result).increment();
            });
        } catch (RuntimeException e) {
            active.remove(username);
            throw e;
        }
        sale.arm();
        return sale;
    }

    /** Sessions open on this instance right now. */
    public int openSessions() {
        return active.size();
    }

    private LiveSale.Timers.Cancel after(long millis, Runnable task) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("[VOICE] live timer failed: {}", e.getClass().getSimpleName());
            }
        }, millis, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}
