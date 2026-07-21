package com.bejo.service;

import com.bejo.constant.AdCampaignState;
import com.bejo.dto.request.AdDownloadReportRequestDto;
import com.bejo.dto.request.AdLocationReportRequestDto;
import com.bejo.dto.request.AdPlayedReportRequestDto;
import com.bejo.dto.request.CreateAdCampaignRequestDto;
import com.bejo.dto.response.AdCampaignResponseDto;
import com.bejo.dto.response.AdDeliveryResponseDto;
import com.bejo.entity.AdCampaign;
import com.bejo.entity.AdDelivery;
import com.bejo.entity.Session;
import com.bejo.exception.ForbiddenException;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.push.AdsPushPayload;
import com.bejo.push.PushPayload;
import com.bejo.repository.AdCampaignRepository;
import com.bejo.repository.AdDeliveryRepository;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Voice-ad campaigns: create (upload the audio + record the target), prepare (tell the device to
 * download it), play (tell it to play), and the device's reports back at each step. A campaign's
 * lifecycle lives on its {@link AdDelivery} (see {@link AdCampaignState}); a campaign fans out to
 * one device today, many by location later.
 *
 * <p><b>Who does what:</b> the admin surface (create/prepare/play/list — gated by the ads admin
 * token in the controller) drives the campaign; the device (JWT-authenticated) reports on a
 * delivery only if it is that delivery's target. Every push is <b>best-effort</b> and sent
 * <b>after the state change commits</b> — a rolled-back transition must never buzz a device about
 * a campaign step that didn't happen.
 */
@Service
public class AdsCampaignService {

    private static final Logger log = LoggerFactory.getLogger(AdsCampaignService.class);

    // Audio formats the duration inspector (jaudiotagger) can reliably read the length of.
    // The content-type gate matches this so an unsupported format fails clearly at the gate
    // rather than later with a misleading "can't read duration".
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "audio/mpeg", "audio/mp3", "audio/mp4", "audio/m4a", "audio/x-m4a",
            "audio/ogg", "audio/wav", "audio/x-wav");

    @Value("${app.ads.signed-url-ttl-seconds:86400}")
    private long signedUrlTtlSeconds;

    // Max decoded audio size (default 2 MB) — a 10s voice clip is far smaller; the cap
    // rejects an oversized upload cheaply, before it ever reaches storage.
    @Value("${app.ads.max-audio-bytes:2097152}")
    private long maxAudioBytes;

    // Max audio duration in seconds (default 10). Enforced on the bytes in hand, before upload.
    @Value("${app.ads.max-audio-seconds:10}")
    private int maxAudioSeconds;

    private final AdCampaignRepository campaignRepository;
    private final AdDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final AdsAudioStorage audioStorage;
    private final AdsAudioInspector audioInspector;
    private final PushNotificationService pushNotificationService;

    public AdsCampaignService(AdCampaignRepository campaignRepository,
                              AdDeliveryRepository deliveryRepository,
                              UserRepository userRepository,
                              SessionRepository sessionRepository,
                              AdsAudioStorage audioStorage,
                              AdsAudioInspector audioInspector,
                              PushNotificationService pushNotificationService) {
        this.campaignRepository = campaignRepository;
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.audioStorage = audioStorage;
        this.audioInspector = audioInspector;
        this.pushNotificationService = pushNotificationService;
    }

    // ── Admin: create ────────────────────────────────────────────────────────
    /**
     * Uploads the audio to storage and records the campaign + its one delivery (state CREATED).
     * Validates the target is a real device (an active user with that session) so a typo fails
     * fast rather than pushing into the void.
     */
    @Transactional
    public AdCampaignResponseDto create(CreateAdCampaignRequestDto request) {
        String contentType = request.contentType().trim().toLowerCase();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Tipe audio tidak didukung (gunakan MP3/M4A/OGG/WAV)");
        }

        byte[] audio;
        try {
            audio = Base64.getDecoder().decode(request.audioBase64());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Format audio tidak valid");
        }
        if (audio.length == 0) {
            throw new IllegalArgumentException("Audio tidak boleh kosong");
        }
        if (audio.length > maxAudioBytes) {
            throw new IllegalArgumentException("Ukuran audio maksimal " + (maxAudioBytes / 1024 / 1024) + "MB");
        }

        // Target must be an active user with a registered device that can actually receive a
        // push — fail the typo (and a device that never registered its FCM token) now, rather
        // than pushing into the void at prepare/play time.
        if (!userRepository.existsByUsernameAndDeletedAtIsNull(request.targetUsername())) {
            throw new IllegalArgumentException("Target pengguna tidak ditemukan");
        }
        Session session = sessionRepository.findByUsernameAndDeviceId(request.targetUsername(), request.targetDeviceId())
                .orElseThrow(() -> new IllegalArgumentException("Perangkat target tidak ditemukan"));
        if (session.getPushToken() == null || session.getPushToken().isBlank()) {
            throw new IllegalArgumentException("Perangkat target belum terdaftar notifikasi");
        }

        // Enforce the duration limit on the bytes in hand, before anything is uploaded.
        String extension = extensionFor(contentType);
        int durationSeconds = audioInspector.durationSeconds(audio, extension);
        if (durationSeconds > maxAudioSeconds) {
            throw new IllegalArgumentException("Durasi audio maksimal " + maxAudioSeconds + " detik");
        }

        String sha256 = sha256Hex(audio);
        // Organized under a per-campaign folder: campaigns/<uuid>/audio.<ext>
        String objectName = "campaigns/" + UUID.randomUUID() + "/audio." + extension;

        AdCampaign campaign = new AdCampaign();
        campaign.setName(request.name().trim());
        campaign.setBrand(trimToNull(request.brand()));
        campaign.setAudioObject(objectName);
        campaign.setAudioContentType(contentType);
        campaign.setAudioSha256(sha256);
        campaign.setAudioSizeBytes((long) audio.length);
        campaignRepository.save(campaign);

        AdDelivery delivery = new AdDelivery();
        delivery.setCampaignId(campaign.getId());
        delivery.setTargetUsername(request.targetUsername());
        delivery.setTargetDeviceId(request.targetDeviceId());
        delivery.setState(AdCampaignState.CREATED);
        deliveryRepository.save(delivery);

        // Upload LAST — after the rows are persisted. GCS isn't transactional, so uploading
        // first would leave an orphaned object if a save then failed and rolled the tx back.
        // If this upload throws, the tx rolls back and no object was created.
        audioStorage.put(objectName, audio, contentType);

        log.info("[ADS] Created campaign id={} name='{}' target={}/{} ({} bytes)",
                campaign.getId(), campaign.getName(), request.targetUsername(), request.targetDeviceId(), audio.length);
        return toDto(campaign, List.of(delivery));
    }

    // ── Admin: prepare (send the download push) ──────────────────────────────
    /**
     * Moves each eligible delivery to PREPARING and pushes a PREPARE (with a fresh signed
     * download URL) to its device after commit. 400 if no delivery is in a preparable state.
     */
    @Transactional
    public AdCampaignResponseDto prepare(Long campaignId) {
        AdCampaign campaign = requireCampaign(campaignId);
        List<AdDelivery> deliveries = deliveryRepository.findByCampaignId(campaignId);
        LocalDateTime now = LocalDateTime.now();

        int prepared = 0;
        for (AdDelivery d : deliveries) {
            if (!AdCampaignState.PREPARABLE.contains(d.getState())) continue;
            d.setState(AdCampaignState.PREPARING);
            d.setPreparedAt(now);
            deliveryRepository.save(d);
            prepared++;

            String audioUrl = audioStorage.signedDownloadUrl(
                    campaign.getAudioObject(), Duration.ofSeconds(signedUrlTtlSeconds));
            pushAfterCommit(d.getTargetUsername(), d.getTargetDeviceId(), AdsPushPayload.prepare(campaign, d, audioUrl),
                    "PREPARE campaignId=" + campaignId + " deliveryId=" + d.getId());
        }
        if (prepared == 0) {
            throw new IllegalArgumentException("Kampanye tidak dapat disiapkan pada status saat ini");
        }
        log.info("[ADS] Prepared campaignId={} deliveries={}", campaignId, prepared);
        return toDto(campaign, deliveries);
    }

    // ── Admin: play (send the play push) ─────────────────────────────────────
    /**
     * Moves each downloaded (READY) delivery to STARTING and pushes a PLAY after commit.
     * 400 if no delivery is ready to play.
     */
    @Transactional
    public AdCampaignResponseDto play(Long campaignId) {
        AdCampaign campaign = requireCampaign(campaignId);
        List<AdDelivery> deliveries = deliveryRepository.findByCampaignId(campaignId);
        LocalDateTime now = LocalDateTime.now();

        int started = 0;
        for (AdDelivery d : deliveries) {
            if (!AdCampaignState.PLAYABLE.contains(d.getState())) continue;
            d.setState(AdCampaignState.STARTING);
            d.setStartedAt(now);
            deliveryRepository.save(d);
            started++;

            pushAfterCommit(d.getTargetUsername(), d.getTargetDeviceId(), AdsPushPayload.play(campaign, d),
                    "PLAY campaignId=" + campaignId + " deliveryId=" + d.getId());
        }
        if (started == 0) {
            throw new IllegalArgumentException("Kampanye belum siap diputar");
        }
        log.info("[ADS] Started campaignId={} deliveries={}", campaignId, started);
        return toDto(campaign, deliveries);
    }

    // ── Device: report download ──────────────────────────────────────────────
    /**
     * The app's download report: success + a matching size/hash moves PREPARING → READY, a
     * failure or mismatch moves it → ERROR. Idempotent — a duplicate report of an already-settled
     * delivery is a no-op returning its current state (400 only if it was never prepared).
     */
    @Transactional
    public AdDeliveryResponseDto reportDownloaded(String username, Long deliveryId, AdDownloadReportRequestDto request) {
        AdDelivery delivery = requireOwnedDelivery(username, deliveryId);
        AdCampaign campaign = requireCampaign(delivery.getCampaignId());
        LocalDateTime now = LocalDateTime.now();

        boolean ok = Boolean.TRUE.equals(request.success()) && integrityMatches(campaign, request);
        int changed;
        if (ok) {
            changed = deliveryRepository.markReady(deliveryId, now, request.sha256(), request.sizeBytes());
        } else {
            String reason = Boolean.TRUE.equals(request.success())
                    ? "Hash atau ukuran file tidak cocok"
                    : (trimToNull(request.error()) != null ? request.error().trim() : "Unduhan gagal");
            changed = deliveryRepository.markError(deliveryId, now, reason, request.sha256(), request.sizeBytes());
        }
        if (changed == 0 && AdCampaignState.CREATED.equals(delivery.getState())) {
            throw new IllegalArgumentException("Kampanye belum disiapkan");
        }
        log.info("[ADS] Download report deliveryId={} success={} -> {}",
                deliveryId, request.success(), ok ? "READY" : (changed > 0 ? "ERROR" : delivery.getState()));
        return toDto(reload(deliveryId));
    }

    // ── Device: report played ────────────────────────────────────────────────
    /**
     * The app played the ad. With a location the delivery goes STARTING → FINISHED in one step;
     * without one it goes → PLAYED and waits for the location (step 8). Idempotent.
     */
    @Transactional
    public AdDeliveryResponseDto reportPlayed(String username, Long deliveryId, AdPlayedReportRequestDto request) {
        AdDelivery delivery = requireOwnedDelivery(username, deliveryId);
        LocalDateTime now = LocalDateTime.now();

        // A play time well in the future means a wrong/forged device clock (mirrors presence).
        if (request.playedAt().isAfter(now.plusDays(1))) {
            throw new IllegalArgumentException("Waktu putar tidak valid");
        }
        // Location is optional, but a lone coordinate is meaningless — require both or neither.
        boolean hasLat = request.latitude() != null;
        boolean hasLng = request.longitude() != null;
        if (hasLat != hasLng) {
            throw new IllegalArgumentException("latitude dan longitude harus diisi bersama");
        }
        boolean hasLocation = hasLat && hasLng;

        int changed;
        if (hasLocation) {
            changed = deliveryRepository.markPlayedAndFinished(deliveryId, request.playedAt(), now,
                    request.latitude(), request.longitude());
            if (changed == 0) {
                // Already PLAYED (an earlier report had no location) — salvage the location now
                // instead of dropping it, same effect as the /location call.
                changed = deliveryRepository.markFinishedWithLocation(deliveryId, now,
                        request.latitude(), request.longitude());
            }
        } else {
            changed = deliveryRepository.markPlayed(deliveryId, request.playedAt());
        }

        if (changed == 0 && !isPlayedOrLater(delivery.getState())) {
            throw new IllegalArgumentException("Iklan belum diminta untuk diputar");
        }
        log.info("[ADS] Played report deliveryId={} hasLocation={}", deliveryId, hasLocation);
        return toDto(reload(deliveryId));
    }

    // ── Device: report deferred location ─────────────────────────────────────
    /**
     * The play location the {@code played} report couldn't include (foreground-only), arriving on
     * its own call: PLAYED → FINISHED. Idempotent — a no-op if the delivery is already FINISHED.
     */
    @Transactional
    public AdDeliveryResponseDto reportLocation(String username, Long deliveryId, AdLocationReportRequestDto request) {
        AdDelivery delivery = requireOwnedDelivery(username, deliveryId);
        LocalDateTime now = LocalDateTime.now();

        int changed = deliveryRepository.markFinishedWithLocation(
                deliveryId, now, request.latitude(), request.longitude());
        if (changed == 0 && !AdCampaignState.FINISHED.equals(delivery.getState())) {
            throw new IllegalArgumentException("Iklan belum diputar");
        }
        log.info("[ADS] Location report deliveryId={}", deliveryId);
        return toDto(reload(deliveryId));
    }

    // ── Admin: read ──────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public Page<AdCampaignResponseDto> list(Pageable pageable) {
        Page<AdCampaign> page = campaignRepository.findAllByOrderByIdDesc(pageable);
        if (page.isEmpty()) return page.map(c -> toDto(c, List.of()));

        List<Long> ids = page.getContent().stream().map(AdCampaign::getId).toList();
        // Batch the deliveries in one query (no N+1), then group by campaign
        Map<Long, List<AdDelivery>> byCampaign = new HashMap<>();
        for (AdDelivery d : deliveryRepository.findByCampaignIdIn(ids)) {
            byCampaign.computeIfAbsent(d.getCampaignId(), k -> new ArrayList<>()).add(d);
        }
        return page.map(c -> toDto(c, byCampaign.getOrDefault(c.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public AdCampaignResponseDto get(Long campaignId) {
        AdCampaign campaign = requireCampaign(campaignId);
        return toDto(campaign, deliveryRepository.findByCampaignId(campaignId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean integrityMatches(AdCampaign campaign, AdDownloadReportRequestDto request) {
        return request.sha256() != null
                && request.sha256().equalsIgnoreCase(campaign.getAudioSha256())
                && request.sizeBytes() != null
                && request.sizeBytes().equals(campaign.getAudioSizeBytes());
    }

    private static boolean isPlayedOrLater(String state) {
        return AdCampaignState.PLAYED.equals(state) || AdCampaignState.FINISHED.equals(state);
    }

    private AdCampaign requireCampaign(Long id) {
        return campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Kampanye tidak ditemukan: " + id));
    }

    // Load a delivery and check the caller is its target device's owner
    private AdDelivery requireOwnedDelivery(String username, Long deliveryId) {
        AdDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Iklan tidak ditemukan: " + deliveryId));
        if (!delivery.getTargetUsername().equals(username)) {
            throw new ForbiddenException("Iklan ini bukan untuk Anda");
        }
        return delivery;
    }

    private AdDelivery reload(Long deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Iklan tidak ditemukan: " + deliveryId));
    }

    /**
     * Send the push only after the state change commits (see {@link PresenceService} for the full
     * rationale): an inline push could fire before a rollback and buzz a device about a step that
     * didn't stick. Best-effort — a failure is logged, never rethrown. Outside a transaction
     * (a unit test calling the service directly) it sends immediately.
     */
    private void pushAfterCommit(String username, String deviceId, PushPayload payload, String scope) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendQuietly(username, deviceId, payload, scope);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendQuietly(username, deviceId, payload, scope);
            }
        });
    }

    private void sendQuietly(String username, String deviceId, PushPayload payload, String scope) {
        try {
            // Log the delivered count so a "sent but nothing arrived" (dead token, signing/FCM
            // issue) is visible in Cloud Run logs — 0 means the push was not delivered.
            int sent = pushNotificationService.sendToDevice(username, deviceId, payload);
            log.info("[ADS] Push delivered={} for {}", sent, scope);
        } catch (RuntimeException e) {
            log.error("[ADS] Push failed for {} {} (state still recorded): {}", username, scope, e.getMessage(), e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tidak tersedia", e); // never on a standard JRE
        }
    }

    // Maps an allowed content type (see ALLOWED_CONTENT_TYPES) to the temp-file extension the
    // duration inspector needs to pick the right reader.
    private static String extensionFor(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a";
            case "audio/ogg" -> "ogg";
            case "audio/wav", "audio/x-wav" -> "wav";
            default -> "bin";
        };
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private AdCampaignResponseDto toDto(AdCampaign c, List<AdDelivery> deliveries) {
        List<AdDeliveryResponseDto> deliveryDtos = deliveries.stream().map(this::toDto).toList();
        // Convenience mirror: with exactly one delivery, surface its state at the top level
        String state = deliveryDtos.size() == 1 ? deliveryDtos.get(0).state() : null;
        return new AdCampaignResponseDto(c.getId(), c.getName(), c.getBrand(), c.getAudioContentType(),
                c.getAudioSha256(), c.getAudioSizeBytes(), state, c.getCreatedAt(), deliveryDtos);
    }

    private AdDeliveryResponseDto toDto(AdDelivery d) {
        return new AdDeliveryResponseDto(d.getId(), d.getCampaignId(), d.getTargetUsername(), d.getTargetDeviceId(),
                d.getState(), d.getErrorReason(), d.getReportedSha256(), d.getReportedSizeBytes(),
                d.getPreparedAt(), d.getReadyAt(), d.getStartedAt(), d.getPlayedAt(), d.getFinishedAt(),
                d.getErroredAt(), d.getPlayLatitude(), d.getPlayLongitude(), d.getCreatedAt());
    }
}
