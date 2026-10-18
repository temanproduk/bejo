package com.bejo.voice.service;

import java.time.Duration;
import java.util.Optional;

/**
 * Where the voice-converter model files live and how a device is let in to fetch them. The production implementation is
 * {@link GcsVoiceModelStorage}; the functional tests bind an in-memory double so they need no bucket.
 */
public interface VoiceModelStorage {

    /**
     * The object's bytes, or empty when it was never uploaded. Throws {@link IllegalStateException} <em>without a cause</em> when
     * storage is not configured (an expected state, logged as a warning); any other {@link RuntimeException} (credentials,
     * permissions, network, a storage-client error) is an unexpected failure. {@code VoiceModelsService} maps both to a 503 and
     * logs the second kind as an error with its root cause.
     */
    Optional<byte[]> read(String objectName);

    /**
     * A time-limited URL the device can GET to download the object directly. The bucket blocks public access, so this is the only way in.
     * May throw any {@link RuntimeException} (credentials that cannot sign, a refused IAM call); the service maps it to a 503.
     */
    String signedDownloadUrl(String objectName, Duration ttl);
}
