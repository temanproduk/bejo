package com.bejo.campaign.domain;

/**
 * The {@code adEvent} field an ads push carries (alongside {@code type=}{@link PushType#ADS}) —
 * how the app tells the two ad pushes apart. Both are data-only.
 *
 * <ul>
 *   <li>{@link #PREPARE} — download the audio now; the payload carries {@code campaignId},
 *       {@code deliveryId}, {@code audioUrl} (a short-lived signed URL), {@code sha256},
 *       {@code sizeBytes}, {@code contentType}.</li>
 *   <li>{@link #PLAY} — play the already-downloaded audio; the payload carries
 *       {@code campaignId}, {@code deliveryId}.</li>
 * </ul>
 */
public final class AdEvent {

    public static final String PREPARE = "PREPARE";
    public static final String PLAY = "PLAY";

    private AdEvent() {}
}
