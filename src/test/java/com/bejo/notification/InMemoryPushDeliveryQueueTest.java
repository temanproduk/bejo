package com.bejo.notification;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class InMemoryPushDeliveryQueueTest {

    private static PushJob job() {
        return new PushJob("token", Map.of("type", "TEST"), false, 1L, "device-1", "test");
    }

    @Test
    void enqueuedJobsAreDeliveredByWorkers() throws Exception {
        PushJobSender sender = mock(PushJobSender.class);
        AtomicInteger delivered = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(5);
        doAnswer(inv -> { delivered.incrementAndGet(); latch.countDown(); return null; }).when(sender).deliver(any());

        InMemoryPushDeliveryQueue queue = new InMemoryPushDeliveryQueue(sender, 2, 100);
        for (int i = 0; i < 5; i++) queue.enqueue(job());

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered.get()).isEqualTo(5);
    }

    @Test
    void aFullQueueRunsTheJobOnTheCallerInsteadOfDropping() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        AtomicReference<Thread> lastThread = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();

        PushJobSender sender = mock(PushJobSender.class);
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();   // the single worker is now busy
                block.await();
            } else {
                lastThread.set(Thread.currentThread());
            }
            return null;
        }).when(sender).deliver(any());

        InMemoryPushDeliveryQueue queue = new InMemoryPushDeliveryQueue(sender, 1, 1);
        Thread caller = Thread.currentThread();
        queue.enqueue(job());          // picked up by the worker, which blocks
        firstStarted.await();
        queue.enqueue(job());          // fills the capacity-1 queue
        queue.enqueue(job());          // queue full → CallerRunsPolicy runs it on THIS thread

        assertThat(lastThread.get()).isEqualTo(caller);   // not dropped — the caller ran it
        block.countDown();
    }
}
