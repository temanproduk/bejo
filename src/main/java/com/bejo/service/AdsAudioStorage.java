package com.bejo.service;

import java.time.Duration;

/**
 * Stores ad-campaign audio and hands out short-lived download links for it. The production
 * implementation is {@link GcsAdsAudioStorage} (Google Cloud Storage); the functional tests
 * bind an in-memory stub so they need no bucket.
 */
public interface AdsAudioStorage {

    /** Upload the audio bytes under {@code objectName} with the given content type. */
    void put(String objectName, byte[] content, String contentType);

    /**
     * A time-limited URL the device can GET to download the object directly (no API bandwidth).
     * The bucket blocks public access, so this signed URL is the only way in.
     */
    String signedDownloadUrl(String objectName, Duration ttl);
}
