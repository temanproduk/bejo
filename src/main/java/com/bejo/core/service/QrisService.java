package com.bejo.core.service;

import com.bejo.core.domain.QrisType;
import com.bejo.core.dto.QrisResponseDto;
import com.bejo.core.domain.Business;
import com.bejo.core.domain.BusinessQris;
import com.bejo.core.domain.UserQris;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.core.repository.BusinessQrisRepository;
import com.bejo.core.repository.BusinessRepository;
import com.bejo.core.repository.UserQrisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;

@Service
public class QrisService {

    private static final Logger log = LoggerFactory.getLogger(QrisService.class);

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

    @Transactional
    public QrisResponseDto getQrisData(String username) {
        Long businessId = businessContext.requireBusinessId(username);
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));

        // If the main account assigned this user their own QRIS, serve that (their terminal's image);
        // otherwise fall back to the shared business QRIS below.
        UserQris own = userQrisRepository.findByBusinessIdAndUsername(businessId, username).orElse(null);
        if (own != null) {
            log.debug("[QRIS] Serving per-user QRIS for username={} terminalId={}", username, own.getTerminalId());
            return new QrisResponseDto(QrisType.UPLOADED, own.getQrisImage(),
                    business.getName(), business.getAddress(), business.getNmid());
        }

        BusinessQris qris = businessQrisRepository.findByBusinessId(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("QRIS data not found"));

        if (qris.getQrisImage() != null) {
            log.debug("[QRIS] Serving UPLOADED image for businessId={}", businessId);
            return new QrisResponseDto(QrisType.UPLOADED, qris.getQrisImage(),
                    business.getName(), business.getAddress(), business.getNmid());
        }

        // No uploaded image — serve the managed/initial image as-is. It may be null
        // (e.g. a freshly registered business that hasn't uploaded a QRIS yet), in
        // which case the client renders an empty state instead of a placeholder.
        String initialImage = qris.getInitialQrisImage();
        log.debug("[QRIS] Serving MANAGED image (present={}) for businessId={}", initialImage != null, businessId);
        return new QrisResponseDto(QrisType.MANAGED, initialImage,
                business.getName(), business.getAddress(), business.getNmid());
    }

    @Transactional
    public void uploadQris(String username, String base64Image) {
        Long businessId = businessContext.requireSuperAdminBusinessId(username);  // super-admin only
        validateBase64Image(base64Image);

        // QRIS is optional: a business has no business_qris row until it uploads one,
        // so create it on first upload (qris_code auto-generated).
        BusinessQris qris = businessQrisRepository.findByBusinessId(businessId)
                .orElseGet(() -> {
                    BusinessQris created = new BusinessQris();
                    created.setBusinessId(businessId);
                    log.info("[QRIS] Creating QRIS row on first upload for businessId={}", businessId);
                    return created;
                });

        qris.setQrisImage(base64Image);
        businessQrisRepository.save(qris);
        log.info("[QRIS] Uploaded custom image ({} chars) for businessId={}", base64Image.length(), businessId);
    }

    @Transactional
    public void resetQris(String username) {
        Long businessId = businessContext.requireSuperAdminBusinessId(username);  // super-admin only
        BusinessQris qris = businessQrisRepository.findByBusinessId(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("QRIS data not found"));

        // Clear the uploaded image; fall back to whatever initial/managed image
        // exists (may be null → empty state). No placeholder is generated.
        qris.setQrisImage(null);
        businessQrisRepository.save(qris);
        log.info("[QRIS] Reset to MANAGED image for businessId={}", businessId);
    }

    private void validateBase64Image(String value) {
        try {
            Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Format gambar QRIS tidak valid");
        }
    }
}
