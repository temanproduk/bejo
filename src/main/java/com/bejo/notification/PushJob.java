package com.bejo.notification;

import java.util.Map;

/**
 * A single queued FCM delivery, as plain <b>data</b> (not a closure) so it can cross a process
 * boundary — the in-memory queue passes it between threads today, and a Redis/Kafka implementation
 * could JSON-serialize it onto a topic tomorrow with no change to producers or the sender.
 *
 * @param token        the target device's FCM token
 * @param data         the data-only payload ({@link PushPayload#toData()})
 * @param urgent       whether to send at Android priority HIGH ({@link PushPayload#urgent()})
 * @param recipientRef an <b>opaque</b> handle to whoever owns this token, carried back to the
 *                     {@link StaleTokenHandler} if FCM rejects it. Notification never interprets
 *                     it — the core adapter that produced it knows it's a {@code bejo_sessions} row.
 * @param deviceId     for logging
 * @param scope        for logging (who this fan-out was for)
 */
public record PushJob(
        String token,
        Map<String, String> data,
        boolean urgent,
        Long recipientRef,
        String deviceId,
        String scope
) {}
