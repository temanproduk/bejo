package com.bejo.notification;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link PushDeliveryQueue}: a bounded worker pool drains queued {@link PushJob}s and
 * hands each to {@link PushJobSender}. The slow part of a push is the FCM network call, so the
 * fan-out (resolving which devices to notify) stays on the request/callback thread while the sends
 * run here — the request returns without waiting on FCM, and bursts queue instead of piling up on
 * request threads.
 *
 * <p><b>Bounded, never dropped:</b> a fixed pool drains a capacity-bounded queue; if the queue is
 * full the submitting thread runs the send itself ({@code CallerRunsPolicy}) — back-pressure, not
 * loss. It's in-memory, so anything still queued at shutdown/restart is lost — acceptable, since
 * pushes are best-effort. Swapping this for a durable Redis/Kafka implementation is a matter of
 * another {@link PushDeliveryQueue} that publishes the job and a consumer that calls the same
 * {@link PushJobSender}.
 */
@Component
public class InMemoryPushDeliveryQueue implements PushDeliveryQueue {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPushDeliveryQueue.class);

    private final PushJobSender sender;
    private final ThreadPoolExecutor pool;

    public InMemoryPushDeliveryQueue(PushJobSender sender,
                                     @Value("${app.push.queue.threads:4}") int threads,
                                     @Value("${app.push.queue.capacity:1000}") int capacity) {
        this.sender = sender;
        this.pool = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(capacity),
                namedDaemonFactory("push-delivery-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        log.info("[PUSH-QUEUE] Started in-memory queue ({} threads, capacity {})", threads, capacity);
    }

    @Override
    public void enqueue(PushJob job) {
        pool.execute(() -> sender.deliver(job));
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
