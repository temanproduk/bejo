package com.bejo.service;

import com.bejo.constant.QrisType;
import com.bejo.dto.response.QrisResponseDto;
import com.bejo.entity.User;
import com.bejo.entity.UserQris;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.UserQrisRepository;
import com.bejo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;

@Service
public class QrisService {

    private static final Logger log = LoggerFactory.getLogger(QrisService.class);

    private final UserRepository userRepository;
    private final UserQrisRepository userQrisRepository;

    public QrisService(UserRepository userRepository, UserQrisRepository userQrisRepository) {
        this.userRepository = userRepository;
        this.userQrisRepository = userQrisRepository;
    }

    @Transactional
    public QrisResponseDto getQrisData(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        UserQris qris = userQrisRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("QRIS data not found"));

        if (qris.getQrisImage() != null) {
            log.info("[QRIS] Serving UPLOADED image for username={}", username);
            return new QrisResponseDto(QrisType.UPLOADED, qris.getQrisImage(),
                    user.getBusinessName(), user.getBusinessAddress(), user.getNmid());
        }

        // No uploaded image — serve the managed/initial image as-is. It may be null
        // (e.g. a freshly registered account that hasn't uploaded a QRIS yet), in
        // which case the client renders an empty state instead of a placeholder.
        String initialImage = qris.getInitialQrisImage();
        log.info("[QRIS] Serving MANAGED image (present={}) for username={}", initialImage != null, username);
        return new QrisResponseDto(QrisType.MANAGED, initialImage,
                user.getBusinessName(), user.getBusinessAddress(), user.getNmid());
    }

    @Transactional
    public void uploadQris(String username, String base64Image) {
        validateBase64Image(base64Image);

        // QRIS is optional: SELF_SERVICE accounts have no user_qris row until they
        // upload one, so create it on first upload (qris_code auto-generated).
        UserQris qris = userQrisRepository.findByUsername(username)
                .orElseGet(() -> {
                    UserQris created = new UserQris();
                    created.setUsername(username);
                    log.info("[QRIS] Creating QRIS row on first upload for username={}", username);
                    return created;
                });

        qris.setQrisImage(base64Image);
        userQrisRepository.save(qris);
        log.info("[QRIS] Uploaded custom image ({} chars) for username={}", base64Image.length(), username);
    }

    @Transactional
    public void resetQris(String username) {
        UserQris qris = userQrisRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("QRIS data not found"));

        // Clear the uploaded image; fall back to whatever initial/managed image
        // exists (may be null → empty state). No placeholder is generated.
        qris.setQrisImage(null);
        userQrisRepository.save(qris);
        log.info("[QRIS] Reset to MANAGED image for username={}", username);
    }

    private void validateBase64Image(String value) {
        try {
            Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("qrisImage bukan format base64 yang valid");
        }
    }
}
