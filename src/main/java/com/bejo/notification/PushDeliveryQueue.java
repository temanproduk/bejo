package com.bejo.notification;

/**
 * The producer-facing seam for outbound FCM delivery: callers {@link #enqueue} a {@link PushJob}
 * and move on. The only implementation today is {@link InMemoryPushDeliveryQueue} (a bounded worker
 * pool), but because the contract is "enqueue serializable data" — not "run this closure" — a
 * Redis- or Kafka-backed implementation can drop in later without touching any producer, sharing
 * the same {@link PushJobSender} on the consumer side.
 */
public interface PushDeliveryQueue {

    /** Hand a delivery to the queue. Best-effort — implementations must not throw to the caller. */
    void enqueue(PushJob job);
}
