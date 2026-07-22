package com.bejo.ads.service;

import com.bejo.ads.service.AdsAudioStorage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link AdsAudioStorage} — keeps audio in a map and hands out a fake URL, so the
 * functional tests exercise the ads flow without a real Cloud Storage bucket or credentials.
 * Active only under the {@code test} profile, replacing {@code GcsAdsAudioStorage} (which is
 * {@code @Profile("!test")}).
 */
@Service
@Profile("test")
public class InMemoryAdsAudioStorage implements AdsAudioStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String objectName, byte[] content, String contentType) {
        objects.put(objectName, content);
    }

    @Override
    public String signedDownloadUrl(String objectName, Duration ttl) {
        return "https://storage.example.test/" + objectName + "?ttl=" + ttl.toSeconds();
    }

    public boolean has(String objectName) {
        return objects.containsKey(objectName);
    }
}
