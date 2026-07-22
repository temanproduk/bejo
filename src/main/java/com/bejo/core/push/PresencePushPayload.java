package com.bejo.core.push;

import com.bejo.notification.PushType;
import com.bejo.core.domain.Presence;
import com.bejo.notification.AbstractPushPayload;

import java.util.Map;

/**
 * An attendance event — a clock-in/out to the super admin, or a ping to a member. Urgent, so the
 * app wakes and can show its dialog / build its own heads-up notification on arrival.
 */
public final class PresencePushPayload extends AbstractPushPayload {

    private final Presence presence;

    public PresencePushPayload(Presence presence, String title, String body) {
        super(PushType.PRESENCE, title, body);
        this.presence = presence;
    }

    @Override
    public boolean urgent() {
        return true;
    }

    @Override
    protected void putExtra(Map<String, String> data) {
        data.put("presenceType", presence.getType());
        data.put("presenceId", String.valueOf(presence.getId()));
        data.put("userId", String.valueOf(presence.getUserId()));
        data.put("username", presence.getUsername());
        data.put("clockDatetime", String.valueOf(presence.getClockDatetime()));
    }
}
