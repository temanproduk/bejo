package com.bejo.notification;

/**
 * One device the notification module can deliver to, as the notification module sees it: a token
 * plus an <b>opaque</b> reference back to whoever owns it. The module never inspects
 * {@code recipientRef} — it only hands it back to the {@link StaleTokenHandler} when FCM rejects
 * the token, so the owning module (core, which resolved it from {@code bejo_sessions}) can clean up.
 * This is what lets notification stay free of any session/user knowledge.
 *
 * @param token        the device's FCM token (never blank — the directory drops token-less devices)
 * @param recipientRef opaque owner handle (the core adapter knows it's a session id)
 * @param deviceId     for logging only
 */
public record PushRecipient(String token, Long recipientRef, String deviceId) {}
