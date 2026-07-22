package com.bejo.notification;

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
 *   <li>{@link #ADS} — a voice-ad campaign event; the payload carries {@code adEvent}
 *       ({@link AdEvent}: PREPARE / PLAY), {@code campaignId}, {@code deliveryId}, and for
 *       PREPARE the download details ({@code audioUrl}, {@code sha256}, {@code sizeBytes})</li>
 * </ul>
 */
public final class PushType {

    public static final String TRANSACTION = "TRANSACTION";
    public static final String PRESENCE = "PRESENCE";
    public static final String ADS = "ADS";

    private PushType() {}
}
