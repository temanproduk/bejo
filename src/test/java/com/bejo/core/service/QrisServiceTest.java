package com.bejo.core.service;

import com.bejo.core.dto.QrisResponseDto;
import com.bejo.core.domain.Business;
import com.bejo.core.domain.BusinessQris;
import com.bejo.core.domain.User;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.core.repository.BusinessQrisRepository;
import com.bejo.core.repository.UserQrisRepository;
import com.bejo.core.repository.BusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    // Sample EMV payloads — a QRIS is stored as a code now, not a base64 image
    private static final String CODE = "00020101021126610014COM.BEJO.WWW0118936008220000000001520458125303360540810005802ID5909Toko Test6007Jakarta63041234";
    private static final String OTHER_CODE = "00020101021126610014COM.BEJO.WWW0118936008229999999995204581253033605802ID5909Toko Baru6007Jakarta6304ABCD";
    private static final String INITIAL_CODE = "00020101021126610014COM.BEJO.WWW0118936008221111111115204581253033605802ID5909Managed QR6007Jakarta6304EEEE";

    private BusinessQris qris(String ownCode, String initialCode) {
        BusinessQris q = new BusinessQris();
        q.setBusinessId(BIZ);
        q.setQrisCode("11111111-2222-3333-4444-555555555555");   // vestigial internal UUID
        q.setQrisPayload(ownCode);
        q.setInitialQrisPayload(initialCode);
        return q;
    }

    @Test
    void uploadedCodeYieldsUploadedType() {
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business()));
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(qris(CODE, null)));

        QrisResponseDto data = qrisService.getQrisData("alice");

        assertThat(data.type()).isEqualTo("UPLOADED");
        assertThat(data.qrisCode()).isEqualTo(CODE);
        assertThat(data.businessName()).isEqualTo("Warung Alice");
    }

    @Test
    void managedTypeWithoutCodeServesEmptyState() {
        BusinessQris q = qris(null, null);
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business()));
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(q));

        QrisResponseDto data = qrisService.getQrisData("alice");

        assertThat(data.type()).isEqualTo("MANAGED");
        // No placeholder is generated — the initial code (null here) is served as-is
        assertThat(data.qrisCode()).isNull();
        verify(businessQrisRepository, never()).save(q);
    }

    @Test
    void managedTypeReusesExistingInitialCode() {
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business()));
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(qris(null, INITIAL_CODE)));

        QrisResponseDto data = qrisService.getQrisData("alice");

        assertThat(data.type()).isEqualTo("MANAGED");
        assertThat(data.qrisCode()).isEqualTo(INITIAL_CODE);
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
    void uploadStoresTheQrisCode() {
        BusinessQris q = qris(null, INITIAL_CODE);
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(q));

        qrisService.uploadQris("alice", OTHER_CODE);

        assertThat(q.getQrisPayload()).isEqualTo(OTHER_CODE);
        verify(businessQrisRepository).save(q);
    }

    @Test
    void uploadRejectsAnUnusableCode() {
        // Validation runs before any repository lookup, so no stubbing is needed
        assertThatThrownBy(() -> qrisService.uploadQris("alice", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wajib diisi");
        // Control characters aren't a QR payload
        assertThatThrownBy(() -> qrisService.uploadQris("alice", "0002\u0000BAD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tidak valid");
        // A base64 image is orders of magnitude longer than any EMV payload
        assertThatThrownBy(() -> qrisService.uploadQris("alice", "A".repeat(2000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terlalu panjang");
    }

    @Test
    void uploadCreatesQrisRowWhenNoneExists() {
        // A business has no business_qris row until its first upload
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.empty());

        qrisService.uploadQris("alice", OTHER_CODE);

        ArgumentCaptor<BusinessQris> captor = ArgumentCaptor.forClass(BusinessQris.class);
        verify(businessQrisRepository).save(captor.capture());
        assertThat(captor.getValue().getBusinessId()).isEqualTo(BIZ);
        assertThat(captor.getValue().getQrisPayload()).isEqualTo(OTHER_CODE);
    }

    @Test
    void nonSuperAdminCannotUploadOrReset() {
        when(businessContext.requireSuperAdminBusinessId("bob"))
                .thenThrow(new com.bejo.shared.exception.ForbiddenException("Hanya super admin yang dapat mengubah pengaturan bisnis"));

        assertThatThrownBy(() -> qrisService.uploadQris("bob", OTHER_CODE))
                .isInstanceOf(com.bejo.shared.exception.ForbiddenException.class);
        assertThatThrownBy(() -> qrisService.resetQris("bob"))
                .isInstanceOf(com.bejo.shared.exception.ForbiddenException.class);
        verify(businessQrisRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resetClearsUploadedCode() {
        BusinessQris q = qris(CODE, INITIAL_CODE);
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(q));

        qrisService.resetQris("alice");

        assertThat(q.getQrisPayload()).isNull();
        assertThat(q.getInitialQrisPayload()).isEqualTo(INITIAL_CODE);
        verify(businessQrisRepository).save(q);
    }

    @Test
    void resetClearsUploadedCodeWithoutGenerating() {
        BusinessQris q = qris(CODE, null);
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(q));

        qrisService.resetQris("alice");

        assertThat(q.getQrisPayload()).isNull();
        // No initial code is generated — stays null (empty state)
        assertThat(q.getInitialQrisPayload()).isNull();
        verify(businessQrisRepository).save(q);
    }

    // ── hasOwnQris: does this account monitor a QRIS of its own? ──

    private User asUser(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setBusinessId(BIZ);
        u.setBusinessRole(role);
        return u;
    }

    @Test
    void superAdminHasOwnQrisOnceTheBusinessHasOne() {
        when(userQrisRepository.existsByBusinessIdAndUsername(BIZ, "admin")).thenReturn(false);
        when(businessQrisRepository.existsByBusinessId(BIZ)).thenReturn(true);

        assertThat(qrisService.hasOwnQris(asUser("admin", com.bejo.core.domain.BusinessRole.SUPER_ADMIN))).isTrue();
    }

    @Test
    void superAdminWithoutAnyBusinessQrisDoesNot() {
        when(userQrisRepository.existsByBusinessIdAndUsername(BIZ, "admin")).thenReturn(false);
        when(businessQrisRepository.existsByBusinessId(BIZ)).thenReturn(false);

        assertThat(qrisService.hasOwnQris(asUser("admin", com.bejo.core.domain.BusinessRole.SUPER_ADMIN))).isFalse();
    }

    @Test
    void memberWithAnAssignedQrisDoes() {
        when(userQrisRepository.existsByBusinessIdAndUsername(BIZ, "emp")).thenReturn(true);

        assertThat(qrisService.hasOwnQris(asUser("emp", com.bejo.core.domain.BusinessRole.MEMBER))).isTrue();
    }

    @Test
    void memberSharingTheBusinessQrisDoesNot() {
        when(userQrisRepository.existsByBusinessIdAndUsername(BIZ, "emp")).thenReturn(false);
        // Even though the business has a QRIS — it is the super admin who monitors it
        assertThat(qrisService.hasOwnQris(asUser("emp", com.bejo.core.domain.BusinessRole.MEMBER))).isFalse();
        verify(businessQrisRepository, never()).existsByBusinessId(any());
    }

    @Test
    void aStoredCodeIsServedAsTheCode() {
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business()));
        when(businessQrisRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(qris(CODE, null)));

        QrisResponseDto data = qrisService.getQrisData("alice");

        assertThat(data.qrisCode()).isEqualTo(CODE);
    }
}
