package com.bejo.push;

import java.util.Map;

/**
 * A push notification's content, polymorphic by kind. Every push Bejo sends is data-only (the
 * app renders it), so a payload is just the {@code data} map plus whether it needs urgent
 * (high-priority) delivery. {@link com.bejo.service.PushNotificationService} takes a
 * {@code PushPayload} and never a raw map, so each kind owns how it's built and how it's sent —
 * a new push type is a new implementation, not another hand-built map at a call site.
 *
 * @see AbstractPushPayload for the common {@code type}/{@code title}/{@code body} handling
 * @see TransactionPushPayload
 * @see PresencePushPayload
 * @see AdsPushPayload
 */
public interface PushPayload {

    /** The full data-only payload sent to FCM (includes {@code type}, {@code title}, {@code body}). */
    Map<String, String> toData();

    /**
     * True if FCM should deliver this at Android priority {@code HIGH} — waking the app promptly
     * even in Doze. Used for pushes the app must act on at once (presence, ads); false lets FCM
     * batch until the device next wakes (payments). Defaults to normal priority.
     */
    default boolean urgent() {
        return false;
    }
}
