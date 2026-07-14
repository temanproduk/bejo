package com.bejo.constant;

/**
 * Canonical set of {@code presences.type} values — what produced the presence row.
 *
 * <ul>
 *   <li>{@link #CLOCK_IN} — a member started their shift ({@code POST /api/presences/clock-in})</li>
 *   <li>{@link #CLOCK_OUT} — a member ended their shift ({@code POST /api/presences/clock-out})</li>
 *   <li>{@link #PING} — a spot-check the super admin sent to a member
 *       ({@code POST /api/presences/pings}); the member acknowledges it via
 *       {@code POST /api/presences/{id}/respond}, which stamps {@code response_datetime}</li>
 * </ul>
 */
public final class PresenceType {

    public static final String CLOCK_IN = "CLOCK_IN";
    public static final String CLOCK_OUT = "CLOCK_OUT";
    public static final String PING = "PING";

    private PresenceType() {}
}
