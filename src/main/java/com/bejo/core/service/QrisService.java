package com.bejo.core.service;

import com.bejo.core.domain.QrisType;
import com.bejo.core.dto.QrisResponseDto;
import com.bejo.core.domain.Business;
import com.bejo.core.domain.BusinessQris;
import com.bejo.core.domain.User;
import com.bejo.core.domain.UserQris;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.core.repository.BusinessQrisRepository;
import com.bejo.core.repository.BusinessRepository;
import com.bejo.core.repository.UserQrisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class QrisService {

    private static final Logger log = LoggerFactory.getLogger(QrisService.class);

    /**
     * Longest accepted QRIS code. Real EMV payloads run a few hundred characters; this also
     * separates a code from a pre-switch base64 image, which is orders of magnitude longer.
     */
    static final int MAX_QRIS_CODE_LENGTH = 1024;

    private final BusinessRepository businessRepository;
    private final BusinessQrisRepository businessQrisRepository;
    private final UserQrisRepository userQrisRepository;
    private final BusinessContext businessContext;

    public QrisService(BusinessRepository businessRepository,
                       BusinessQrisRepository businessQrisRepository,
                       UserQrisRepository userQrisRepository,
                       BusinessContext businessContext) {
        this.businessRepository = businessRepository;
        this.businessQrisRepository = businessQrisRepository;
        this.userQrisRepository = userQrisRepository;
        this.businessContext = businessContext;
    }

    /**
     * Whether this user has a QRIS <b>of their own to monitor</b> — the {@code hasOwnQris} flag on
     * the login / {@code /me} bundles. The app uses it to decide whether to offer the
     * "sound on incoming payment detected by another app" toggle, which only makes sense for an
     * account that actually runs a QRIS merchant app and sees its own payment notifications:
     * <ul>
     *   <li><b>Super admin</b> — the shared business QRIS is effectively theirs, so this is true
     *       once the business has one (registration creates none, so it is false until the first
     *       upload). Also true if they were additionally given their own {@code user_qris}.</li>
     *   <li><b>Member</b> — true only when the super admin assigned them their <b>own</b>
     *       {@code user_qris}. A member sharing the business QRIS doesn't run the merchant app
     *       (the super admin does), so it is false for them.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public boolean hasOwnQris(User user) {
        Long businessId = user.getBusinessId();
        if (userQrisRepository.existsByBusinessIdAndUsername(businessId, user.getUsername())) {
            return true;
        }
        return user.isSuperAdmin() && businessQrisRepository.existsByBusinessId(businessId);
    }

    /**
     * The caller's effective QRIS: their own assigned one if the main account gave them a
     * terminal, otherwise the shared business QRIS.
     *
     * <p>Serves the <b>QRIS code</b> (EMV payload) — the app renders the QR from it, so nothing
     * image-sized crosses the wire. Merchants onboarded before the switch still have a base64
     * image in that column and no code; the server has no QR decoder, so it cannot convert them.
     * Their value is returned as {@code legacyQrisImage} instead, and disappears once they
     * re-upload.
     */
    @Transactional
    public QrisResponseDto getQrisData(String username) {
        Long businessId = businessContext.requireBusinessId(username);
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));

        UserQris own = userQrisRepository.findByBusinessIdAndUsername(businessId, username).orElse(null);
        if (own != null) {
            log.debug("[QRIS] Serving per-user QRIS for username={} terminalId={}", username, own.getTerminalId());
            return response(QrisType.UPLOADED, own.getQrisPayload(), business);
        }

        BusinessQris qris = businessQrisRepository.findByBusinessId(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("QRIS data not found"));

        if (qris.getQrisPayload() != null) {
            log.debug("[QRIS] Serving UPLOADED QRIS for businessId={}", businessId);
            return response(QrisType.UPLOADED, qris.getQrisPayload(), business);
        }

        // Nothing uploaded — serve the managed/initial value as-is. It may be null (a freshly
        // registered business that hasn't supplied a QRIS), in which case the client renders an
        // empty state rather than a placeholder.
        String initial = qris.getInitialQrisPayload();
        log.debug("[QRIS] Serving MANAGED QRIS (present={}) for businessId={}", initial != null, businessId);
        return response(QrisType.MANAGED, initial, business);
    }

    /**
     * Splits a stored value into the right response field. A QRIS code is a few hundred
     * characters of EMV payload; anything longer is a base64 image from before the switch, which
     * the app has to render as an image rather than encode into a QR.
     */
    private QrisResponseDto response(String type, String stored, Business business) {
        boolean legacyImage = isLegacyImage(stored);
        return new QrisResponseDto(
                type,
                legacyImage ? null : stored,
                legacyImage ? stored : null,
                business.getName(), business.getAddress(), business.getNmid());
    }

    @Transactional
    public void uploadQris(String username, String qrisCode) {
        Long businessId = businessContext.requireSuperAdminBusinessId(username);  // super-admin only
        String code = validateQrisCode(qrisCode);

        // QRIS is optional: a business has no business_qris row until it supplies one,
        // so create it on first upload (the internal qris_code UUID is auto-generated).
        BusinessQris qris = businessQrisRepository.findByBusinessId(businessId)
                .orElseGet(() -> {
                    BusinessQris created = new BusinessQris();
                    created.setBusinessId(businessId);
                    log.info("[QRIS] Creating QRIS row on first upload for businessId={}", businessId);
                    return created;
                });

        qris.setQrisPayload(code);
        businessQrisRepository.save(qris);
        log.info("[QRIS] Stored QRIS code ({} chars) for businessId={}", code.length(), businessId);
    }

    @Transactional
    public void resetQris(String username) {
        Long businessId = businessContext.requireSuperAdminBusinessId(username);  // super-admin only
        BusinessQris qris = businessQrisRepository.findByBusinessId(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("QRIS data not found"));

        // Clear the merchant's own code; fall back to whatever initial/managed value exists
        // (may be null → empty state). No placeholder is generated.
        qris.setQrisPayload(null);
        businessQrisRepository.save(qris);
        log.info("[QRIS] Reset to MANAGED QRIS for businessId={}", businessId);
    }

    /**
     * A QRIS code is the EMV payload the app decoded from the merchant's QR. Validation is
     * deliberately light — length and printability — because rejecting an unusual but valid
     * payload would block a real merchant, and the app has already parsed it once.
     */
    /**
     * True when a stored value is a pre-switch base64 image rather than a QRIS code. Codes are
     * capped at {@link #MAX_QRIS_CODE_LENGTH}; a base64 image is orders of magnitude longer.
     */
    static boolean isLegacyImage(String stored) {
        return stored != null && stored.length() > MAX_QRIS_CODE_LENGTH;
    }

    static String validateQrisCode(String value) {
        String code = value.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("Kode QRIS wajib diisi");
        }
        if (code.length() > MAX_QRIS_CODE_LENGTH) {
            throw new IllegalArgumentException("Kode QRIS terlalu panjang (maksimal "
                    + MAX_QRIS_CODE_LENGTH + " karakter)");
        }
        if (code.chars().anyMatch(c -> c < 0x20 || c > 0x7E)) {
            throw new IllegalArgumentException("Format kode QRIS tidak valid");
        }
        return code;
    }
}
