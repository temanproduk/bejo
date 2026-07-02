package com.bejo.service;

import com.bejo.dto.request.ChangePasswordRequest;
import com.bejo.dto.request.UpdateBankRequest;
import com.bejo.dto.request.UpdateBusinessRequest;
import com.bejo.dto.request.UpdateProfileRequest;
import com.bejo.entity.User;
import com.bejo.entity.UserBank;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.UserBankRepository;
import com.bejo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void updateProfileOverwritesFields() {
        User user = new User();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        userService.updateProfile("alice", new UpdateProfileRequest("New Name", "new@example.com", "+628999999999"));

        assertThat(user.getName()).isEqualTo("New Name");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getPhone()).isEqualTo("+628999999999");
    }

    @Test
    void updateProfileForUnknownUserThrows() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateProfile("ghost",
                new UpdateProfileRequest("Name", "a@b.com", "+628111111111")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void changePasswordRejectsWrongOldPassword() {
        User user = new User();
        user.setPasswordHash("$old");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$old")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword("alice",
                new ChangePasswordRequest("wrong", "newpass123")))
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

        userService.changePassword("alice", new ChangePasswordRequest("oldpass1", "newpass123"));

        assertThat(user.getPasswordHash()).isEqualTo("$new");
    }

    @Test
    void updateBankOverwritesFields() {
        UserBank bank = new UserBank();
        when(userBankRepository.findByUsername("alice")).thenReturn(Optional.of(bank));

        userService.updateBank("alice", new UpdateBankRequest("Mandiri", "9876543210", "Alice B"));

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

        userService.updateBusiness("alice", new UpdateBusinessRequest("Toko Baru", "Bandung", "ID999"));

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
}
