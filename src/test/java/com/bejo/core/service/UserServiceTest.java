package com.bejo.core.service;

import com.bejo.core.domain.BusinessRole;
import com.bejo.core.dto.ChangePasswordRequestDto;
import com.bejo.core.dto.RegisterRequestDto;
import com.bejo.core.dto.UpdateBankRequestDto;
import com.bejo.core.dto.UpdateBusinessRequestDto;
import com.bejo.core.dto.UpdateProfileRequestDto;
import com.bejo.core.domain.Business;
import com.bejo.core.domain.BusinessBank;
import com.bejo.core.domain.User;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.core.repository.BusinessBankRepository;
import com.bejo.core.repository.BusinessRepository;
import com.bejo.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Long BIZ = 1L;

    @Mock private UserRepository userRepository;
    @Mock private BusinessRepository businessRepository;
    @Mock private BusinessBankRepository businessBankRepository;
    @Mock private BusinessContext businessContext;
    @Mock private AccountSettingsService accountSettingsService;
    @Mock private PasswordEncoder passwordEncoder;

    // Real (not mocked): a simple config-backed value object
    private static final BusinessOptions BUSINESS_OPTIONS = new BusinessOptions(
            java.util.List.of("Mikro", "Kecil", "Menengah", "Besar", "Non-Profit"), "Mikro",
            java.util.List.of("Makanan & Minuman", "Otomotif", "Asuransi"), "Makanan & Minuman");

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, businessRepository, businessBankRepository,
                businessContext, accountSettingsService, BUSINESS_OPTIONS, passwordEncoder);
        lenient().when(businessContext.requireBusinessId("alice")).thenReturn(BIZ);           // reads / business+bank edits
        lenient().when(businessContext.requireSuperAdminBusinessId("alice")).thenReturn(BIZ);  // picture mutations (super admin)
    }

    private RegisterRequestDto registerRequest() {
        return new RegisterRequestDto("alice", "password1", "Alice", "alice@example.com",
                "+628111111111", "Warung Alice", null, null, null, null, null, null, null, null, null);
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userRepository.existsByUsernameAndDeletedAtIsNull("alice")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(registerRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Username sudah digunakan");

        verify(userRepository, never()).save(any());
        verify(businessRepository, never()).save(any());
    }

    @Test
    void registerCreatesBusinessSuperAdminAndBank() {
        when(userRepository.existsByUsernameAndDeletedAtIsNull("alice")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("$encoded");

        userService.register(registerRequest());

        ArgumentCaptor<Business> businessCaptor = ArgumentCaptor.forClass(Business.class);
        verify(businessRepository).save(businessCaptor.capture());
        assertThat(businessCaptor.getValue().getName()).isEqualTo("Warung Alice");
        // size/industry omitted → configured defaults
        assertThat(businessCaptor.getValue().getSize()).isEqualTo("Mikro");
        assertThat(businessCaptor.getValue().getIndustry()).isEqualTo("Makanan & Minuman");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("$encoded");
        assertThat(userCaptor.getValue().getAccountType()).isEqualTo("SELF_SERVICE");
        assertThat(userCaptor.getValue().getBusinessRole()).isEqualTo(BusinessRole.SUPER_ADMIN);

        ArgumentCaptor<BusinessBank> bankCaptor = ArgumentCaptor.forClass(BusinessBank.class);
        verify(businessBankRepository).save(bankCaptor.capture());
        // Bank fields not supplied at registration default to empty strings
        assertThat(bankCaptor.getValue().getBankName()).isEmpty();
    }

    @Test
    void getMeReturnsProfileBusinessBankPictureAndSettings() {
        User user = new User();
        user.setUsername("alice");
        user.setName("Alice");
        user.setEmail("alice@example.com");
        user.setPhone("+628111111111");
        user.setBusinessId(BIZ);
        user.setBusinessRole(com.bejo.core.domain.BusinessRole.SUPER_ADMIN);
        Business business = new Business();
        business.setName("Warung Alice");
        business.setPicture("aW1n");
        BusinessBank bank = new BusinessBank();
        bank.setBankName("BCA");
        when(businessContext.requireUser("alice")).thenReturn(user);
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business));
        when(businessBankRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(bank));
        when(accountSettingsService.getSettings("alice")).thenReturn(
                new com.bejo.core.dto.AccountSettingsResponseDto(
                        java.util.Map.of(), java.util.List.of(), java.util.List.of(), true, false, false, false, false, false));

        var me = userService.getMe("alice");

        assertThat(me.username()).isEqualTo("alice");
        assertThat(me.businessRole()).isEqualTo("SUPER_ADMIN");
        assertThat(me.businessName()).isEqualTo("Warung Alice");
        assertThat(me.bankName()).isEqualTo("BCA");
        assertThat(me.profilePicture()).isEqualTo("aW1n");
        assertThat(me.accountSettings()).isNotNull();
    }

    @Test
    void registerAppliesProvidedSizeIndustryAndStorePhoto() {
        when(userRepository.existsByUsernameAndDeletedAtIsNull("alice")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("$encoded");
        String photo = java.util.Base64.getEncoder().encodeToString("img".getBytes());
        var req = new RegisterRequestDto("alice", "password1", "Alice", "alice@example.com",
                "+628111111111", "Warung Alice", null, null, null, null, null, "Kecil", "Otomotif", photo, null);

        userService.register(req);

        ArgumentCaptor<Business> captor = ArgumentCaptor.forClass(Business.class);
        verify(businessRepository).save(captor.capture());
        assertThat(captor.getValue().getSize()).isEqualTo("Kecil");
        assertThat(captor.getValue().getIndustry()).isEqualTo("Otomotif");
        assertThat(captor.getValue().getStorePhoto()).isEqualTo(photo);
    }

    @Test
    void registerStoresMerchantPanTrimmingBlankToNull() {
        when(userRepository.existsByUsernameAndDeletedAtIsNull("alice")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("$encoded");
        var req = new RegisterRequestDto("alice", "password1", "Alice", "alice@example.com",
                "+628111111111", "Warung Alice", null, null, null, null, null, null, null, null, "9360082232606290012");

        userService.register(req);

        ArgumentCaptor<Business> captor = ArgumentCaptor.forClass(Business.class);
        verify(businessRepository).save(captor.capture());
        assertThat(captor.getValue().getMerchantPan()).isEqualTo("9360082232606290012");
    }

    @Test
    void registerRejectsInvalidSizeOrIndustry() {
        when(userRepository.existsByUsernameAndDeletedAtIsNull("alice")).thenReturn(false);
        var badSize = new RegisterRequestDto("alice", "password1", "Alice", "alice@example.com",
                "+628111111111", "Warung Alice", null, null, null, null, null, "Raksasa", null, null, null);
        assertThatThrownBy(() -> userService.register(badSize))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size tidak valid");

        var badIndustry = new RegisterRequestDto("alice", "password1", "Alice", "alice@example.com",
                "+628111111111", "Warung Alice", null, null, null, null, null, null, "Antariksa", null, null);
        assertThatThrownBy(() -> userService.register(badIndustry))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("industry tidak valid");
        verify(businessRepository, never()).save(any());
    }

    @Test
    void updateProfileOverwritesFields() {
        User user = new User();
        when(businessContext.requireUser("alice")).thenReturn(user);

        userService.updateProfile("alice", new UpdateProfileRequestDto("New Name", "new@example.com", "+628999999999"));

        assertThat(user.getName()).isEqualTo("New Name");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getPhone()).isEqualTo("+628999999999");
    }

    @Test
    void updateProfileForUnknownUserThrows() {
        when(businessContext.requireUser("ghost")).thenThrow(new ResourceNotFoundException("User not found"));

        assertThatThrownBy(() -> userService.updateProfile("ghost",
                new UpdateProfileRequestDto("Name", "a@b.com", "+628111111111")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void changePasswordRejectsWrongOldPassword() {
        User user = new User();
        user.setPasswordHash("$old");
        when(businessContext.requireUser("alice")).thenReturn(user);
        when(passwordEncoder.matches("wrong", "$old")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword("alice",
                new ChangePasswordRequestDto("wrong", "newpass123")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(user.getPasswordHash()).isEqualTo("$old");
    }

    @Test
    void changePasswordEncodesNewPassword() {
        User user = new User();
        user.setPasswordHash("$old");
        when(businessContext.requireUser("alice")).thenReturn(user);
        when(passwordEncoder.matches("oldpass1", "$old")).thenReturn(true);
        when(passwordEncoder.encode("newpass123")).thenReturn("$new");

        userService.changePassword("alice", new ChangePasswordRequestDto("oldpass1", "newpass123"));

        assertThat(user.getPasswordHash()).isEqualTo("$new");
    }

    @Test
    void updateBankOverwritesFields() {
        BusinessBank bank = new BusinessBank();
        when(businessBankRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(bank));

        userService.updateBank("alice", new UpdateBankRequestDto("Mandiri", "9876543210", "Alice B"));

        assertThat(bank.getBankName()).isEqualTo("Mandiri");
        assertThat(bank.getBankNumber()).isEqualTo("9876543210");
        assertThat(bank.getBankAccountName()).isEqualTo("Alice B");
    }

    @Test
    void resetBankRestoresInitialValues() {
        BusinessBank bank = new BusinessBank();
        bank.setBankName("Mandiri");
        bank.setInitialBankName("BCA");
        bank.setInitialBankNumber("1234567890");
        bank.setInitialBankAccountName("Alice");
        when(businessBankRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(bank));

        userService.resetBank("alice");

        assertThat(bank.getBankName()).isEqualTo("BCA");
        assertThat(bank.getBankNumber()).isEqualTo("1234567890");
        assertThat(bank.getBankAccountName()).isEqualTo("Alice");
    }

    @Test
    void resetBankWithoutInitialDataThrows() {
        BusinessBank bank = new BusinessBank();
        when(businessBankRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(bank));

        assertThatThrownBy(() -> userService.resetBank("alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateBusinessOverwritesFields() {
        Business business = new Business();
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business));

        userService.updateBusiness("alice", new UpdateBusinessRequestDto("Toko Baru", "Bandung", "ID999"));

        assertThat(business.getName()).isEqualTo("Toko Baru");
        assertThat(business.getAddress()).isEqualTo("Bandung");
        assertThat(business.getNmid()).isEqualTo("ID999");
    }

    @Test
    void resetBusinessRestoresInitialValues() {
        Business business = new Business();
        business.setName("Toko Baru");
        business.setInitialName("Warung Alice");
        business.setInitialAddress("Jakarta");
        business.setInitialNmid("ID123");
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business));

        userService.resetBusiness("alice");

        assertThat(business.getName()).isEqualTo("Warung Alice");
        assertThat(business.getAddress()).isEqualTo("Jakarta");
        assertThat(business.getNmid()).isEqualTo("ID123");
    }

    @Test
    void resetBusinessWithoutInitialDataThrows() {
        Business business = new Business();
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business));

        assertThatThrownBy(() -> userService.resetBusiness("alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getProfilePictureReturnsNullWhenUnset() {
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(new Business()));

        assertThat(userService.getProfilePicture("alice").profilePicture()).isNull();
    }

    @Test
    void getProfilePictureReturnsStoredValue() {
        Business business = new Business();
        business.setPicture("aW1hZ2U=");
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business));

        assertThat(userService.getProfilePicture("alice").profilePicture()).isEqualTo("aW1hZ2U=");
    }

    @Test
    void updateProfilePictureStoresValidBase64() {
        Business business = new Business();
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business));

        var data = userService.updateProfilePicture("alice", "aW1hZ2UtZGF0YQ==");

        assertThat(business.getPicture()).isEqualTo("aW1hZ2UtZGF0YQ==");
        assertThat(data.profilePicture()).isEqualTo("aW1hZ2UtZGF0YQ==");
        verify(businessRepository).save(business);
    }

    @Test
    void updateProfilePictureRejectsInvalidBase64() {
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(new Business()));

        assertThatThrownBy(() -> userService.updateProfilePicture("alice", "@@ not base64 @@"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tidak valid");
        verify(businessRepository, never()).save(any());
    }

    @Test
    void updateProfilePictureRejectsOver1Mb() {
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(new Business()));

        // 1 MB + 1 byte of image data, base64-encoded
        byte[] tooBig = new byte[1_048_577];
        String base64 = java.util.Base64.getEncoder().encodeToString(tooBig);

        assertThatThrownBy(() -> userService.updateProfilePicture("alice", base64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1MB");
        verify(businessRepository, never()).save(any());
    }

    @Test
    void nonSuperAdminCannotChangePicture() {
        when(businessContext.requireSuperAdminBusinessId("bob"))
                .thenThrow(new com.bejo.shared.exception.ForbiddenException("Hanya super admin yang dapat mengubah pengaturan bisnis"));

        assertThatThrownBy(() -> userService.updateProfilePicture("bob", "aW1n"))
                .isInstanceOf(com.bejo.shared.exception.ForbiddenException.class);
        assertThatThrownBy(() -> userService.deleteProfilePicture("bob"))
                .isInstanceOf(com.bejo.shared.exception.ForbiddenException.class);
        verify(businessRepository, never()).save(any());
    }

    @Test
    void deleteProfilePictureClearsValue() {
        Business business = new Business();
        business.setPicture("aW1hZ2U=");
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business));

        userService.deleteProfilePicture("alice");

        assertThat(business.getPicture()).isNull();
        verify(businessRepository).save(business);
    }
}
