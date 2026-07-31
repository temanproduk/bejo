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

import java.nio.charset.StandardCharsets;
import org.springframework.transaction.annotation.Transactional;


@Service
public class QrisService {

    private static final Logger log = LoggerFactory.getLogger(QrisService.class);

    /**
     * Longest accepted QRIS code. Real EMV payloads run a few hundred characters.
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
     * image-sized crosses the wire.
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

    private QrisResponseDto response(String type, String qrisCode, Business business) {
        return new QrisResponseDto(type, qrisCode,
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
     * Validates a QRIS code — the EMV payload the app decoded from the merchant's QR.
     *
     * <p>The contract with the app is that this string round-trips <b>unchanged</b>: it is stored
     * verbatim and served verbatim, so re-encoding it yields a QR that scans identically to the
     * merchant's original. The server therefore does not parse, normalise or rewrite it.
     *
     * <p>Validation is deliberately narrow, rejecting only what cannot be a scanned payload:
     * blank, over-long, or containing control characters (a sign of corruption). Notably it does
     * <b>not</b> require ASCII — EMVCo tag 64 ("Merchant Information — Language Template") exists
     * to carry a merchant's name and city in a local script, so a valid payload may hold non-ASCII
     * text, and rejecting it would lock out a real merchant.
     *
     * <p>A payload whose CRC does not check out is <b>logged but still accepted</b>: the app has
     * already decoded it from a physical QR, so a mismatch more likely means an acquirer we
     * haven't seen than a corrupt code, and refusing it would break that merchant outright.
     */
    static String validateQrisCode(String value) {
        String code = value.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("Kode QRIS wajib diisi");
        }
        if (code.length() > MAX_QRIS_CODE_LENGTH) {
            throw new IllegalArgumentException("Kode QRIS terlalu panjang (maksimal "
                    + MAX_QRIS_CODE_LENGTH + " karakter)");
        }
        if (code.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Format kode QRIS tidak valid");
        }
        if (!crcMatches(code)) {
            log.warn("[QRIS] Code failed its EMV CRC check (accepted anyway) — starts with '{}', length {}",
                    code.substring(0, Math.min(12, code.length())), code.length());
        }
        return code;
    }

    /**
     * Whether the payload's trailing CRC tag agrees with its contents, i.e. the code is intact and
     * will re-encode into the merchant's original QR.
     *
     * <p>Per EMVCo, a payload ends with tag {@code 63} length {@code 04} followed by four hex
     * digits, and the checksum is <b>CRC-16/CCITT-FALSE</b> (polynomial {@code 0x1021}, initial
     * value {@code 0xFFFF}, no reflection, no final XOR) over everything up to and including those
     * {@code 6304} characters. An Android implementation must match this exactly. Returns false
     * when no CRC tag is recognisable, so the caller can treat "unparseable" and "mismatched" alike.
     */
    static boolean crcMatches(String code) {
        if (code.length() < 8 || !code.startsWith("6304", code.length() - 8)) {
            return false;
        }
        String body = code.substring(0, code.length() - 4);
        String stated = code.substring(code.length() - 4);
        return stated.equalsIgnoreCase(crc16CcittFalse(body));
    }

    /** CRC-16/CCITT-FALSE of {@code data}, as four uppercase hex digits. */
    static String crc16CcittFalse(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }
}
