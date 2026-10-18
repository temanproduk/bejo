package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.bejo.shared.exception.TooManyRequestsException;
import com.bejo.voice.dto.VoiceModelFileDto;
import com.bejo.voice.dto.VoiceModelsResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Lists the voice-converter model files for the app ("Suara Kamu Sendiri"). The files and a {@code manifest.json} are uploaded to a
 * private bucket by {@code scripts/upload-voice-models.sh} (planned, not written yet); this service validates the manifest and signs a download URL per file.
 *
 * <p>Any signed-in user may ask, and every call mints links to ~131 MB of downloads, so each user is limited to a few calls in any
 * rolling hour. That caps how many links are issued, not how many bytes leave the bucket: a link can be fetched repeatedly until it
 * expires, so a billing or egress alert on the bucket is the real backstop. The slot is claimed first, so an over-limit caller is refused before any storage access, and given back if the call
 * then fails for any reason (nothing uploaded, a bad manifest, storage or signing unavailable): only a call that produced links
 * counts, so an outage on our side cannot lock users out afterwards. Signed URLs are never logged.
 *
 * <p>Logging: "storage not configured" and "manifest not uploaded" are expected states (WARN); anything else that goes wrong with
 * storage, signing or the manifest is ERROR with the most specific cause and the stack trace, because the client only ever sees a
 * constant 503 and the log is the only place the root cause (IAM denied, API disabled, credentials not a signer...) can be read.
 */
@Service
public class VoiceModelsService {

    private static final Logger log = LoggerFactory.getLogger(VoiceModelsService.class);
    static final String PREFIX = "converter/";
    static final String MANIFEST = PREFIX + "manifest.json";
    /** The converter is a handful of files; a manifest listing more than this is not ours. */
    private static final int MAX_FILES = 8;
    private static final long WINDOW_MS = 3_600_000L;
    /** The longest a V4 signed URL may live (GCS maximum, 7 days); a longer or non-positive configured TTL is not honoured. */
    static final long MAX_TTL_SECONDS = 604_800L;
    static final long DEFAULT_TTL_SECONDS = 3_600L;
    /** Above this many tracked users a call also prunes the users who have been idle for the whole window. */
    static final int MAX_TRACKED_USERS = 10_000;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final String UNAVAILABLE = "Model suara belum tersedia";

    private record Entry(String name, long bytes, String sha256) {}
    private record Manifest(String revision, List<Entry> files) {}

    private final VoiceModelStorage storage;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration ttl;
    private final int maxPerHour;
    private final int maxTrackedUsers;

    // Request times per username, newest last. A user's stale entries are dropped on that user's next call, and idle users are
    // removed by the prune pass in claimSlot once more than maxTrackedUsers are tracked.
    private final ConcurrentHashMap<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    @Autowired
    public VoiceModelsService(VoiceModelStorage storage, ObjectMapper mapper,
                              @Value("${app.voice.models.signed-url-ttl-seconds:3600}") long ttlSeconds,
                              @Value("${app.voice.models.max-requests-per-hour:10}") int maxPerHour) {
        this(storage, mapper, Clock.systemUTC(), ttlSeconds, maxPerHour, MAX_TRACKED_USERS);
    }

    VoiceModelsService(VoiceModelStorage storage, ObjectMapper mapper, Clock clock, long ttlSeconds, int maxPerHour,
                       int maxTrackedUsers) {
        this.storage = storage;
        this.mapper = mapper;
        this.clock = clock;
        this.maxPerHour = maxPerHour;
        this.maxTrackedUsers = maxTrackedUsers;
        // An optional setting must not turn into a permanent 503 (or a failed start) when it is mistyped.
        if (ttlSeconds <= 0 || ttlSeconds > MAX_TTL_SECONDS) {
            log.warn("[VOICE-MODELS] signed-url-ttl-seconds={} is outside 1..{}; using {}", ttlSeconds, MAX_TTL_SECONDS, DEFAULT_TTL_SECONDS);
            ttlSeconds = DEFAULT_TTL_SECONDS;
        }
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public VoiceModelsResponseDto converterModels(String username) {
        long claimedAt = claimSlot(username);
        try {
            Manifest manifest = loadManifest();
            return new VoiceModelsResponseDto(manifest.revision(), sign(manifest));
        } catch (RuntimeException e) {                      // whatever went wrong, the call produced no links: give the slot back
            releaseSlot(username, claimedAt);
            throw e;
        }
    }

    private List<VoiceModelFileDto> sign(Manifest manifest) {
        try {
            List<VoiceModelFileDto> files = new ArrayList<>();
            for (Entry e : manifest.files()) {
                files.add(new VoiceModelFileDto(e.name(), e.bytes(), e.sha256(), storage.signedDownloadUrl(PREFIX + e.name(), ttl)));
            }
            return files;
        } catch (RuntimeException e) {                      // credentials cannot sign, IAM refused, API disabled...
            throw unexpected("cannot sign download links", e);
        }
    }

    /** Logs the root cause (the wrapper's own message, e.g. "Failed to sign the provided bytes", hides it) and answers 503. */
    private static ServiceUnavailableException unexpected(String what, RuntimeException e) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        log.error("[VOICE-MODELS] {}: {}: {}", what, cause.getClass().getSimpleName(), cause.getMessage(), e);
        return new ServiceUnavailableException(UNAVAILABLE);
    }

    private Manifest loadManifest() {
        Optional<byte[]> raw;
        try {
            raw = storage.read(MANIFEST);
        } catch (IllegalStateException e) {
            if (e.getCause() != null) throw unexpected("cannot read the manifest", e);   // e.g. credentials failed to load
            log.warn("[VOICE-MODELS] storage is not configured: {}", e.getMessage());     // the bucket setting is blank
            throw new ServiceUnavailableException(UNAVAILABLE);
        } catch (RuntimeException e) {                      // no credentials, bucket or IAM error, network...
            throw unexpected("cannot read the manifest", e);
        }
        if (raw.isEmpty()) {
            log.warn("[VOICE-MODELS] {} has not been uploaded", MANIFEST);
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
        try {
            return parse(raw.get());
        } catch (IOException | IllegalArgumentException e) {
            log.error("[VOICE-MODELS] manifest is invalid: {}", e.getMessage());
            throw new ServiceUnavailableException(UNAVAILABLE);
        }
    }

    private Manifest parse(byte[] raw) throws IOException {
        JsonNode root = mapper.readTree(raw);
        String revision = text(root, "revision");
        JsonNode files = root.path("files");
        if (!files.isArray() || files.isEmpty()) throw new IllegalArgumentException("no files");
        if (files.size() > MAX_FILES) throw new IllegalArgumentException("more than " + MAX_FILES + " files");
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode f : files) {
            String name = text(f, "name");
            String sha = text(f, "sha256");
            long bytes = f.path("bytes").asLong(0);
            if (!SAFE_NAME.matcher(name).matches() || !SHA256.matcher(sha).matches() || bytes <= 0) {
                throw new IllegalArgumentException("bad entry " + name);
            }
            // Case-insensitively: two names that differ only in case would overwrite each other on a case-insensitive disk.
            if (!seen.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("duplicate file " + name);
            out.add(new Entry(name, bytes, sha.toLowerCase(Locale.ROOT)));
        }
        return new Manifest(revision, out);
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText("");
        if (v.isBlank()) throw new IllegalArgumentException("missing " + field);
        return v;
    }

    /** One request per call, at most {@code maxPerHour} in any rolling hour per user. Returns the time it was recorded at. */
    private long claimSlot(String username) {
        Deque<Long> times = recent.computeIfAbsent(username, u -> new ArrayDeque<>());
        long now;
        synchronized (times) {
            now = clock.millis();                           // inside the lock, so the deque stays ordered oldest to newest
            while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) times.pollFirst();
            if (times.size() >= maxPerHour) {
                throw new TooManyRequestsException("Terlalu sering meminta model suara. Coba lagi nanti.");
            }
            times.addLast(now);
        }
        if (recent.size() > maxTrackedUsers) {
            long pruneAt = now;
            recent.entrySet().removeIf(e -> {
                synchronized (e.getValue()) { return e.getValue().isEmpty() || pruneAt - e.getValue().peekLast() > WINDOW_MS; }
            });
        }
        return now;
    }

    /** How many users currently have a record; the prune pass keeps this bounded. */
    int trackedUsers() {
        return recent.size();
    }

    /** Gives back a slot claimed by a call that then failed to produce links. */
    private void releaseSlot(String username, long claimedAt) {
        Deque<Long> times = recent.get(username);
        if (times != null) {
            synchronized (times) { times.removeFirstOccurrence(claimedAt); }
        }
    }
}
