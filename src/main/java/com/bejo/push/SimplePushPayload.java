package com.bejo.push;

import java.util.Map;

/**
 * A payload assembled from arbitrary fields rather than a domain entity — used by the
 * test-notification endpoints, which send whatever the request specifies. Not tied to any one
 * {@link com.bejo.constant.PushType} (the caller supplies it, or null).
 */
public final class SimplePushPayload extends AbstractPushPayload {

    private final Map<String, String> extra;
    private final boolean urgent;

    public SimplePushPayload(String type, String title, String body, Map<String, String> extra) {
        this(type, title, body, extra, false);
    }

    public SimplePushPayload(String type, String title, String body, Map<String, String> extra, boolean urgent) {
        super(type, title, body);
        this.extra = extra == null ? Map.of() : extra;
        this.urgent = urgent;
    }

    @Override
    public boolean urgent() {
        return urgent;
    }

    @Override
    protected void putExtra(Map<String, String> data) {
        data.putAll(extra);
    }
}
