package com.bejo.push;

import java.util.Map;

/**
 * A single queued FCM delivery, as plain <b>data</b> (not a closure) so it can cross a process
 * boundary — the in-memory queue passes it between threads today, and a Redis/Kafka implementation
 * could JSON-serialize it onto a topic tomorrow with no change to producers or the sender.
 *
 * @param token     the target device's FCM token
 * @param data      the data-only payload ({@link PushPayload#toData()})
 * @param urgent    whether to send at Android priority HIGH ({@link PushPayload#urgent()})
 * @param sessionId the {@code bejo_sessions} row, so a dead token can be cleared (race-safe)
 * @param deviceId  for logging
 * @param scope     for logging (who this fan-out was for)
 */
public record PushJob(
        String token,
        Map<String, String> data,
        boolean urgent,
        Long sessionId,
        String deviceId,
        String scope
) {}
