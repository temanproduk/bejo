package com.bejo.voice.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for {@link VoiceModelStorage}: objects in a map, a fake signed URL. Replaces {@code GcsVoiceModelStorage} under the {@code test} profile. */
@Service
@Profile("test")
public class InMemoryVoiceModelStorage implements VoiceModelStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    public void put(String objectName, String content) {
        objects.put(objectName, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void clear() {
        objects.clear();
    }

    @Override
    public Optional<byte[]> read(String objectName) {
        return Optional.ofNullable(objects.get(objectName));
    }

    @Override
    public String signedDownloadUrl(String objectName, Duration ttl) {
        return "https://storage.example.test/" + objectName + "?ttl=" + ttl.toSeconds();
    }
}
