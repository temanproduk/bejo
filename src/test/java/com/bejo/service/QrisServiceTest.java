package com.bejo.service;

import com.bejo.dto.response.QrisResponseDto;
import com.bejo.entity.User;
import com.bejo.entity.UserQris;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.UserQrisRepository;
import com.bejo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QrisServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserQrisRepository userQrisRepository;

    private QrisService qrisService;

    @BeforeEach
    void setUp() {
        qrisService = new QrisService(userRepository, userQrisRepository);
    }

    private User user() {
        User u = new User();
        u.setUsername("alice");
        u.setBusinessName("Warung Alice");
        u.setBusinessAddress("Jakarta");
        u.setNmid("ID123");
        return u;
    }

    private UserQris qris(String uploadedImage, String initialImage) {
        UserQris q = new UserQris();
        q.setUsername("alice");
        q.setQrisCode("11111111-2222-3333-4444-555555555555");
        q.setQrisImage(uploadedImage);
        q.setInitialQrisImage(initialImage);
        return q;
    }

    @Test
    void uploadedImageYieldsUploadedType() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user()));
        when(userQrisRepository.findByUsername("alice")).thenReturn(Optional.of(qris("dXBsb2FkZWQ=", null)));

        QrisResponseDto data = qrisService.getQrisData("alice");

        assertThat(data.type()).isEqualTo("UPLOADED");
        assertThat(data.qrisImage()).isEqualTo("dXBsb2FkZWQ=");
        assertThat(data.businessName()).isEqualTo("Warung Alice");
    }

    @Test
    void managedTypeGeneratesAndPersistsInitialImageOnFirstAccess() {
        UserQris q = qris(null, null);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user()));
        when(userQrisRepository.findByUsername("alice")).thenReturn(Optional.of(q));

        QrisResponseDto data = qrisService.getQrisData("alice");

        assertThat(data.type()).isEqualTo("MANAGED");
        assertThat(data.qrisImage()).isNotBlank();
        // Generated image is valid base64 PNG
        byte[] png = Base64.getDecoder().decode(data.qrisImage());
        assertThat(png).startsWith(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        verify(userQrisRepository).save(q);
        assertThat(q.getInitialQrisImage()).isEqualTo(data.qrisImage());
    }

    @Test
    void managedTypeReusesExistingInitialImage() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user()));
        when(userQrisRepository.findByUsername("alice")).thenReturn(Optional.of(qris(null, "aW5pdGlhbA==")));

        QrisResponseDto data = qrisService.getQrisData("alice");

        assertThat(data.type()).isEqualTo("MANAGED");
        assertThat(data.qrisImage()).isEqualTo("aW5pdGlhbA==");
        verify(userQrisRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getQrisDataForUnknownUserThrows() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> qrisService.getQrisData("ghost"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void uploadStoresBase64Image() {
        UserQris q = qris(null, "aW5pdGlhbA==");
        when(userQrisRepository.findByUsername("alice")).thenReturn(Optional.of(q));

        qrisService.uploadQris("alice", "bmV3LWltYWdl");

        assertThat(q.getQrisImage()).isEqualTo("bmV3LWltYWdl");
        verify(userQrisRepository).save(q);
    }

    @Test
    void uploadRejectsInvalidBase64() {
        when(userQrisRepository.findByUsername("alice")).thenReturn(Optional.of(qris(null, null)));

        assertThatThrownBy(() -> qrisService.uploadQris("alice", "not base64 !!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void resetClearsUploadedImage() {
        UserQris q = qris("dXBsb2FkZWQ=", "aW5pdGlhbA==");
        when(userQrisRepository.findByUsername("alice")).thenReturn(Optional.of(q));

        qrisService.resetQris("alice");

        assertThat(q.getQrisImage()).isNull();
        assertThat(q.getInitialQrisImage()).isEqualTo("aW5pdGlhbA==");
        verify(userQrisRepository).save(q);
    }

    @Test
    void resetGeneratesInitialImageWhenMissing() {
        UserQris q = qris("dXBsb2FkZWQ=", null);
        when(userQrisRepository.findByUsername("alice")).thenReturn(Optional.of(q));

        qrisService.resetQris("alice");

        assertThat(q.getQrisImage()).isNull();
        assertThat(q.getInitialQrisImage()).isNotBlank();
    }
}
