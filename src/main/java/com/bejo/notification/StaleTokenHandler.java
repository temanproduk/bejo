package com.bejo.notification;

/**
 * A <b>port</b> the notification module owns and core implements. When FCM rejects a token as dead
 * (app uninstalled / token rotated), {@link PushJobSender} reports it here instead of touching the
 * database itself — the owning module (core) clears the token from its {@code bejo_sessions} row.
 * This keeps the notification module free of session/persistence knowledge.
 *
 * <p>Called from a push worker thread with no ambient transaction; the implementation is responsible
 * for its own {@code @Transactional} boundary and for making the clear race-safe.
 */
public interface StaleTokenHandler {

    /**
     * FCM rejected {@code token} as unregistered for the recipient identified by the opaque
     * {@code recipientRef} (from the {@link PushRecipient} that produced the job). Best-effort.
     */
    void onTokenRejected(Long recipientRef, String token);
}
