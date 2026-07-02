package com.bejo.service;

import com.bejo.dto.response.UpdateTokenData;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final SessionRepository sessionRepository;

    public DeviceService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public UpdateTokenData updateFcmToken(String username, String deviceId, String pushToken) {
        log.info("[TOKEN] username={} deviceId={}", username, deviceId);

        int updated = sessionRepository.updatePushToken(username, deviceId, pushToken, LocalDateTime.now());
        if (updated < 1) {
            throw new ResourceNotFoundException("Device ID not found: " + deviceId);
        }

        log.info("[TOKEN] Updated push token for deviceId={}", deviceId);
        return new UpdateTokenData(deviceId, pushToken);
    }
}
