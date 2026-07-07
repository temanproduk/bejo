package com.bejo.service;

import com.bejo.dto.request.ChangePasswordRequestDto;
import com.bejo.dto.request.RegisterRequestDto;
import com.bejo.dto.request.UpdateBankRequestDto;
import com.bejo.dto.request.UpdateBusinessRequestDto;
import com.bejo.dto.request.UpdateProfileRequestDto;
import com.bejo.entity.User;
import com.bejo.entity.UserBank;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.UserBankRepository;
import com.bejo.repository.UserRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserBankRepository userBankRepository;
    @Mock private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, userBankRepository, passwordEncoder);
    }

    private RegisterRequestDto registerRequest() {
        return new RegisterRequestDto("alice", "password1", "Alice", "alice@example.com",
                "+628111111111", "Warung Alice", null, null, null, null, null);
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(registerRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Username sudah digunakan");

        verify(userRepository, never()).save(any());
    }

    @Test
    void registerCreatesSelfServiceUserAndBank() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("$encoded");

        userService.register(registerRequest());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("$encoded");
        assertThat(userCaptor.getValue().getAccountType()).isEqualTo("SELF_SERVICE");

        ArgumentCaptor<UserBank> bankCaptor = ArgumentCaptor.forClass(UserBank.class);
        verify(userBankRepository).save(bankCaptor.capture());
        // Bank fields not supplied at registration default to empty strings
        assertThat(bankCaptor.getValue().getBankName()).isEmpty();
    }

    @Test
    void registerInternalCreatesManagedUser() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("$encoded");

        userService.registerInternal(registerRequest());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getAccountType()).isEqualTo("MANAGED");
        verify(userBankRepository).save(any());
    }

    @Test
    void registerInternalRejectsDuplicateUsername() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> userService.registerInternal(registerRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Username sudah digunakan");

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateProfileOverwritesFields() {
        User user = new User();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        userService.updateProfile("alice", new UpdateProfileRequestDto("New Name", "new@example.com", "+628999999999"));

        assertThat(user.getName()).isEqualTo("New Name");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getPhone()).isEqualTo("+628999999999");
    }

    @Test
    void updateProfileForUnknownUserThrows() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateProfile("ghost",
                new UpdateProfileRequestDto("Name", "a@b.com", "+628111111111")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void changePasswordRejectsWrongOldPassword() {
        User user = new User();
        user.setPasswordHash("$old");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
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
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldpass1", "$old")).thenReturn(true);
        when(passwordEncoder.encode("newpass123")).thenReturn("$new");

        userService.changePassword("alice", new ChangePasswordRequestDto("oldpass1", "newpass123"));

        assertThat(user.getPasswordHash()).isEqualTo("$new");
    }

    @Test
    void updateBankOverwritesFields() {
        UserBank bank = new UserBank();
        when(userBankRepository.findByUsername("alice")).thenReturn(Optional.of(bank));

        userService.updateBank("alice", new UpdateBankRequestDto("Mandiri", "9876543210", "Alice B"));

        assertThat(bank.getBankName()).isEqualTo("Mandiri");
        assertThat(bank.getBankNumber()).isEqualTo("9876543210");
        assertThat(bank.getBankAccountName()).isEqualTo("Alice B");
    }

    @Test
    void resetBankRestoresInitialValues() {
        UserBank bank = new UserBank();
        bank.setBankName("Mandiri");
        bank.setInitialBankName("BCA");
        bank.setInitialBankNumber("1234567890");
        bank.setInitialBankAccountName("Alice");
        when(userBankRepository.findByUsername("alice")).thenReturn(Optional.of(bank));

        userService.resetBank("alice");

        assertThat(bank.getBankName()).isEqualTo("BCA");
        assertThat(bank.getBankNumber()).isEqualTo("1234567890");
        assertThat(bank.getBankAccountName()).isEqualTo("Alice");
    }

    @Test
    void resetBankWithoutInitialDataThrows() {
        UserBank bank = new UserBank();
        when(userBankRepository.findByUsername("alice")).thenReturn(Optional.of(bank));

        assertThatThrownBy(() -> userService.resetBank("alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateBusinessOverwritesFields() {
        User user = new User();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        userService.updateBusiness("alice", new UpdateBusinessRequestDto("Toko Baru", "Bandung", "ID999"));

        assertThat(user.getBusinessName()).isEqualTo("Toko Baru");
        assertThat(user.getBusinessAddress()).isEqualTo("Bandung");
        assertThat(user.getNmid()).isEqualTo("ID999");
    }

    @Test
    void resetBusinessRestoresInitialValues() {
        User user = new User();
        user.setBusinessName("Toko Baru");
        user.setInitialBusinessName("Warung Alice");
        user.setInitialBusinessAddress("Jakarta");
        user.setInitialNmid("ID123");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        userService.resetBusiness("alice");

        assertThat(user.getBusinessName()).isEqualTo("Warung Alice");
        assertThat(user.getBusinessAddress()).isEqualTo("Jakarta");
        assertThat(user.getNmid()).isEqualTo("ID123");
    }

    @Test
    void resetBusinessWithoutInitialDataThrows() {
        User user = new User();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.resetBusiness("alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getProfilePictureReturnsNullWhenUnset() {
        User user = new User();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThat(userService.getProfilePicture("alice").profilePicture()).isNull();
    }

    @Test
    void getProfilePictureReturnsStoredValue() {
        User user = new User();
        user.setProfilePicture("aW1hZ2U=");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThat(userService.getProfilePicture("alice").profilePicture()).isEqualTo("aW1hZ2U=");
    }

    @Test
    void updateProfilePictureStoresValidBase64() {
        User user = new User();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        var data = userService.updateProfilePicture("alice", "aW1hZ2UtZGF0YQ==");

        assertThat(user.getProfilePicture()).isEqualTo("aW1hZ2UtZGF0YQ==");
        assertThat(data.profilePicture()).isEqualTo("aW1hZ2UtZGF0YQ==");
        verify(userRepository).save(user);
    }

    @Test
    void updateProfilePictureRejectsInvalidBase64() {
        User user = new User();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.updateProfilePicture("alice", "@@ not base64 @@"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateProfilePictureRejectsOver1Mb() {
        User user = new User();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        // 1 MB + 1 byte of image data, base64-encoded
        byte[] tooBig = new byte[1_048_577];
        String base64 = java.util.Base64.getEncoder().encodeToString(tooBig);

        assertThatThrownBy(() -> userService.updateProfilePicture("alice", base64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1MB");
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateProfilePictureForUnknownUserThrows() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateProfilePicture("ghost", "aW1hZ2U="))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteProfilePictureClearsValue() {
        User user = new User();
        user.setProfilePicture("aW1hZ2U=");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        userService.deleteProfilePicture("alice");

        assertThat(user.getProfilePicture()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void deleteProfilePictureForUnknownUserThrows() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteProfilePicture("ghost"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
