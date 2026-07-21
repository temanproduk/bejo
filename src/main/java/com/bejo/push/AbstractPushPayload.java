package com.bejo.push;

import java.util.HashMap;
import java.util.Map;

/**
 * Base for the concrete push payloads: handles the three fields every push carries —
 * {@code type} ({@link com.bejo.constant.PushType}), {@code title}, {@code body} — and lets each
 * subclass add its own fields via {@link #putExtra}. Null fields are omitted from the wire.
 */
public abstract class AbstractPushPayload implements PushPayload {

    private final String type;
    private final String title;
    private final String body;

    protected AbstractPushPayload(String type, String title, String body) {
        this.type = type;
        this.title = title;
        this.body = body;
    }

    @Override
    public final Map<String, String> toData() {
        Map<String, String> data = new HashMap<>();
        putIfPresent(data, "type", type);
        putIfPresent(data, "title", title);
        putIfPresent(data, "body", body);
        putExtra(data);
        return data;
    }

    /** Add this payload's type-specific fields (use {@link #putIfPresent} to skip nulls). */
    protected abstract void putExtra(Map<String, String> data);

    protected static void putIfPresent(Map<String, String> data, String key, Object value) {
        if (value != null) {
            data.put(key, String.valueOf(value));
        }
    }
}
