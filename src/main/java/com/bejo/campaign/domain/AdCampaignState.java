package com.bejo.campaign.domain;

import java.util.Set;

/**
 * The lifecycle of an ad delivery — the voice campaign as it travels from the server to one
 * device and back. Although the client thinks of it as the "campaign state", the states below
 * describe one {@code ad_deliveries} row (a campaign fans out to one device today, many by
 * location later), so a campaign's reported state is its delivery's state.
 *
 * <pre>
 *   CREATED ─prepare→ PREPARING ─(app downloaded ok)→ READY ─play→ STARTING ─(app played)→ PLAYED ─(location)→ FINISHED
 *                         └─(download failed / hash mismatch)→ ERROR                   └─(played WITH location)──────────┘
 * </pre>
 *
 * <ul>
 *   <li>{@link #CREATED} — campaign + audio uploaded; nothing pushed yet.</li>
 *   <li>{@link #PREPARING} — the PREPARE push was sent; the app should download the audio.</li>
 *   <li>{@link #READY} — the app reported a good download (size + hash match).</li>
 *   <li>{@link #ERROR} — the app reported the file missing or its hash didn't match.</li>
 *   <li>{@link #SCHEDULED} — reserved for a future scheduler; unused today.</li>
 *   <li>{@link #STARTING} — the PLAY push was sent; the app should play the audio.</li>
 *   <li>{@link #PLAYED} — the app played it but hasn't reported the location yet.</li>
 *   <li>{@link #FINISHED} — played and the play location is in (or none was needed).</li>
 * </ul>
 */
public final class AdCampaignState {

    public static final String CREATED = "CREATED";
    public static final String PREPARING = "PREPARING";
    public static final String READY = "READY";
    public static final String ERROR = "ERROR";
    public static final String SCHEDULED = "SCHEDULED";
    public static final String STARTING = "STARTING";
    public static final String PLAYED = "PLAYED";
    public static final String FINISHED = "FINISHED";

    /** States a (re)prepare may start from — a fresh campaign, a retry after error, or a resend. */
    public static final Set<String> PREPARABLE = Set.of(CREATED, ERROR, PREPARING);

    /** States a play may start from — the audio is downloaded (READY) or a resend of the play push. */
    public static final Set<String> PLAYABLE = Set.of(READY, STARTING);

    private AdCampaignState() {}
}
