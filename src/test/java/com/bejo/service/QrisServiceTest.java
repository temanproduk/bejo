package com.bejo.service;

import com.bejo.dto.response.QrisResponseDto;
import com.bejo.entity.Business;
import com.bejo.entity.BusinessQris;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.BusinessQrisRepository;
import com.bejo.repository.UserQrisRepository;
import com.bejo.repository.BusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QrisServiceTest {

    private static final Long BIZ = 1L;

    @Mock private BusinessRepository businessRepository;
    @Mock private BusinessQrisRepository businessQrisRepository;
    @Mock private UserQrisRepository userQrisRepository;
    @Mock private BusinessContext businessContext;

    private QrisService qrisService;

    @BeforeEach
    void setUp() {
        qrisService = new QrisService(businessRepository, businessQrisRepository, userQrisRepository, businessContext);
        lenient().when(businessContext.requireBusinessId("alice")).thenReturn(BIZ);           // reads
        lenient().when(businessContext.requireSuperAdminBusinessId("alice")).thenReturn(BIZ);  // upload/reset (super admin)
    }

    private Business business() {
        Business b = new Business();
        b.setName("Warung Alice");
        b.setAddress("Jakarta");
        b.setNmid("ID123");
        return b;
    }

    private BusinessQris qris(String uploadedImage, String initialImage) {
        BusinessQris q = new BusinessQris();
        q.setBusinessId(BIZ);
        q.setQrisCode("11111111-2222-3333-4444-555555555555");
        q.setQrisImage(uploadedImage);
        q.setInitialQrisImage(initialImage);
        return q;
    }

    @Test
    void uploadedImageYieldsUploadedType() {
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business()));
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(qris("dXBsb2FkZWQ=", null)));

        QrisResponseDto data = qrisService.getQrisData("alice");

        assertThat(data.type()).isEqualTo("UPLOADED");
        assertThat(data.qrisImage()).isEqualTo("dXBsb2FkZWQ=");
        assertThat(data.businessName()).isEqualTo("Warung Alice");
    }

    @Test
    void managedTypeWithoutImageServesEmptyState() {
        BusinessQris q = qris(null, null);
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business()));
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(q));

        QrisResponseDto data = qrisService.getQrisData("alice");

        assertThat(data.type()).isEqualTo("MANAGED");
        // No placeholder is generated — the initial image (null here) is served as-is
        assertThat(data.qrisImage()).isNull();
        verify(businessQrisRepository, never()).save(q);
    }

    @Test
    void managedTypeReusesExistingInitialImage() {
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business()));
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(qris(null, "aW5pdGlhbA==")));

        QrisResponseDto data = qrisService.getQrisData("alice");

        assertThat(data.type()).isEqualTo("MANAGED");
        assertThat(data.qrisImage()).isEqualTo("aW5pdGlhbA==");
        verify(businessQrisRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getQrisDataForUnknownUserThrows() {
        when(businessContext.requireBusinessId("ghost"))
                .thenThrow(new ResourceNotFoundException("User not found"));

        assertThatThrownBy(() -> qrisService.getQrisData("ghost"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void uploadStoresBase64Image() {
        BusinessQris q = qris(null, "aW5pdGlhbA==");
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(q));

        qrisService.uploadQris("alice", "bmV3LWltYWdl");

        assertThat(q.getQrisImage()).isEqualTo("bmV3LWltYWdl");
        verify(businessQrisRepository).save(q);
    }

    @Test
    void uploadRejectsInvalidBase64() {
        // Validation runs before any repository lookup, so no stubbing is needed
        assertThatThrownBy(() -> qrisService.uploadQris("alice", "not base64 !!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tidak valid");
    }

    @Test
    void uploadCreatesQrisRowWhenNoneExists() {
        // A business has no business_qris row until its first upload
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.empty());

        qrisService.uploadQris("alice", "bmV3LWltYWdl");

        ArgumentCaptor<BusinessQris> captor = ArgumentCaptor.forClass(BusinessQris.class);
        verify(businessQrisRepository).save(captor.capture());
        assertThat(captor.getValue().getBusinessId()).isEqualTo(BIZ);
        assertThat(captor.getValue().getQrisImage()).isEqualTo("bmV3LWltYWdl");
    }

    @Test
    void nonSuperAdminCannotUploadOrReset() {
        when(businessContext.requireSuperAdminBusinessId("bob"))
                .thenThrow(new com.bejo.exception.ForbiddenException("Hanya super admin yang dapat mengubah pengaturan bisnis"));

        assertThatThrownBy(() -> qrisService.uploadQris("bob", "bmV3LWltYWdl"))
                .isInstanceOf(com.bejo.exception.ForbiddenException.class);
        assertThatThrownBy(() -> qrisService.resetQris("bob"))
                .isInstanceOf(com.bejo.exception.ForbiddenException.class);
        verify(businessQrisRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resetClearsUploadedImage() {
        BusinessQris q = qris("dXBsb2FkZWQ=", "aW5pdGlhbA==");
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(q));

        qrisService.resetQris("alice");

        assertThat(q.getQrisImage()).isNull();
        assertThat(q.getInitialQrisImage()).isEqualTo("aW5pdGlhbA==");
        verify(businessQrisRepository).save(q);
    }

    @Test
    void resetClearsUploadedImageWithoutGenerating() {
        BusinessQris q = qris("dXBsb2FkZWQ=", null);
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(q));

        qrisService.resetQris("alice");

        assertThat(q.getQrisImage()).isNull();
        // No initial image is generated — stays null (empty state)
        assertThat(q.getInitialQrisImage()).isNull();
        verify(businessQrisRepository).save(q);
    }
}
