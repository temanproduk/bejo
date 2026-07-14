package com.bejo.constant;

/**
 * The {@code type} field every push's data payload carries — how the app tells one kind of
 * push from another and routes it to the right screen. All pushes are data-only messages
 * (the app renders them itself), so this is the client's only reliable discriminator.
 *
 * <ul>
 *   <li>{@link #TRANSACTION} — a QRIS payment landed (from the payment callback); the payload
 *       carries {@code transactionId}, {@code amount}, {@code status}, …</li>
 *   <li>{@link #PRESENCE} — an attendance event; the payload carries {@code presenceType}
 *       ({@link PresenceType}: CLOCK_IN / CLOCK_OUT / PING), {@code presenceId},
 *       {@code userId}, {@code username}</li>
 * </ul>
 */
public final class PushType {

    public static final String TRANSACTION = "TRANSACTION";
    public static final String PRESENCE = "PRESENCE";

    private PushType() {}
}
