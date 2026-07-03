package com.bejo.service;

import com.bejo.dto.response.UpdateTokenResponseDto;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock private SessionRepository sessionRepository;

    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        deviceService = new DeviceService(sessionRepository);
    }

    @Test
    void updateReturnsNewTokenData() {
        when(sessionRepository.updatePushToken(eq("alice"), eq("dev-1"), eq("new-token"), any()))
                .thenReturn(1);

        UpdateTokenResponseDto data = deviceService.updateFcmToken("alice", "dev-1", "new-token");

        assertThat(data.deviceId()).isEqualTo("dev-1");
        assertThat(data.pushToken()).isEqualTo("new-token");
    }

    @Test
    void updateForUnknownOrForeignDeviceThrows() {
        when(sessionRepository.updatePushToken(eq("alice"), eq("someone-elses-device"), eq("t"), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> deviceService.updateFcmToken("alice", "someone-elses-device", "t"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
