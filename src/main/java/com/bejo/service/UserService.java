package com.bejo.service;

import com.bejo.constant.AccountType;
import com.bejo.dto.request.ChangePasswordRequestDto;
import com.bejo.dto.request.RegisterRequestDto;
import com.bejo.dto.request.UpdateBankRequestDto;
import com.bejo.dto.request.UpdateBusinessRequestDto;
import com.bejo.dto.request.UpdateProfileRequestDto;
import com.bejo.dto.response.BankResponseDto;
import com.bejo.dto.response.BusinessResponseDto;
import com.bejo.dto.response.ProfilePictureResponseDto;
import com.bejo.entity.User;
import com.bejo.entity.UserBank;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.UserBankRepository;
import com.bejo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final UserBankRepository userBankRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       UserBankRepository userBankRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userBankRepository = userBankRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Self-service registration through the public API: creates a
     * {@link AccountType#SELF_SERVICE} account with its {@code users} and
     * {@code user_banks} rows. QRIS is optional — no {@code user_qris} row (and no
     * default image) is created; the user may upload one later via the QRIS API.
     * This is account creation, not authentication — login, logout, and session
     * management live in {@link AuthService}.
     */
    @Transactional
    public void register(RegisterRequestDto request) {
        log.info("[REGISTER] username={}", request.username());
        createUserAndBank(request, AccountType.SELF_SERVICE);
        log.info("[REGISTER] Created SELF_SERVICE user username={}", request.username());
    }

    /**
     * Internal/system registration: identical to self-service registration but marks
     * the account {@link AccountType#MANAGED}. Bank info is still optional and QRIS is
     * still optional (no {@code user_qris} row is created — add one later via upload).
     */
    @Transactional
    public void registerInternal(RegisterRequestDto request) {
        log.info("[REGISTER-INTERNAL] username={}", request.username());
        createUserAndBank(request, AccountType.MANAGED);
        log.info("[REGISTER-INTERNAL] Created MANAGED user username={}", request.username());
    }

    private void createUserAndBank(RegisterRequestDto request, String accountType) {
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Username sudah digunakan");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setBusinessName(request.businessName());
        user.setBusinessAddress(request.businessAddress());
        user.setNmid(request.nmid());
        user.setInitialBusinessName(request.businessName());
        user.setInitialBusinessAddress(request.businessAddress());
        user.setInitialNmid(request.nmid());
        user.setAccountType(accountType);
        user.setEmailVerified(false);
        user.setPhoneVerified(false);
        userRepository.save(user);

        String bankName        = request.bankName()        != null ? request.bankName()        : "";
        String bankNumber      = request.bankNumber()      != null ? request.bankNumber()      : "";
        String bankAccountName = request.bankAccountName() != null ? request.bankAccountName() : "";
        UserBank bank = new UserBank();
        bank.setUsername(request.username());
        bank.setBankName(bankName);
        bank.setBankNumber(bankNumber);
        bank.setBankAccountName(bankAccountName);
        bank.setInitialBankName(bankName);
        bank.setInitialBankNumber(bankNumber);
        bank.setInitialBankAccountName(bankAccountName);
        userBankRepository.save(bank);
    }

    @Transactional
    public void updateProfile(String username, UpdateProfileRequestDto request) {
        log.info("[PROFILE] username={}", username);
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        userRepository.save(user);
        log.info("[PROFILE] Updated for username={}", username);
    }

    @Transactional
    public void updateBusiness(String username, UpdateBusinessRequestDto request) {
        log.info("[BUSINESS] username={}", username);
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setBusinessName(request.businessName());
        user.setBusinessAddress(request.businessAddress());
        user.setNmid(request.nmid());
        userRepository.save(user);
        log.info("[BUSINESS] Updated for username={}", username);
    }

    @Transactional
    public BusinessResponseDto resetBusiness(String username) {
        log.info("[BUSINESS RESET] username={}", username);
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getInitialBusinessName() == null) {
            throw new IllegalArgumentException("Data bisnis awal tidak tersedia untuk direset");
        }
        user.setBusinessName(user.getInitialBusinessName());
        user.setBusinessAddress(user.getInitialBusinessAddress());
        user.setNmid(user.getInitialNmid());
        userRepository.save(user);
        log.info("[BUSINESS RESET] Reverted for username={}", username);
        return new BusinessResponseDto(user.getBusinessName(), user.getBusinessAddress(), user.getNmid());
    }

    @Transactional
    public void updateBank(String username, UpdateBankRequestDto request) {
        log.info("[BANK] username={}", username);
        UserBank bank = userBankRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Bank data not found"));
        bank.setBankName(request.bankName());
        bank.setBankNumber(request.bankNumber());
        bank.setBankAccountName(request.bankAccountName());
        userBankRepository.save(bank);
        log.info("[BANK] Updated for username={}", username);
    }

    @Transactional
    public BankResponseDto resetBank(String username) {
        log.info("[BANK RESET] username={}", username);
        UserBank bank = userBankRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Bank data not found"));
        if (bank.getInitialBankName() == null) {
            throw new IllegalArgumentException("Data rekening awal tidak tersedia untuk direset");
        }
        bank.setBankName(bank.getInitialBankName());
        bank.setBankNumber(bank.getInitialBankNumber());
        bank.setBankAccountName(bank.getInitialBankAccountName());
        userBankRepository.save(bank);
        log.info("[BANK RESET] Reverted for username={}", username);
        return new BankResponseDto(bank.getBankName(), bank.getBankNumber(), bank.getBankAccountName());
    }

    @Transactional
    public void changePassword(String username, ChangePasswordRequestDto request) {
        log.info("[PASSWORD] username={}", username);
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Password saat ini tidak sesuai");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        log.info("[PASSWORD] Changed for username={}", username);
    }

    // 1 MB — the maximum size of the decoded profile picture
    private static final int MAX_PROFILE_PICTURE_BYTES = 1_048_576;

    @Transactional(readOnly = true)
    public ProfilePictureResponseDto getProfilePicture(String username) {
        log.info("[PROFILE-PIC] Get for username={}", username);
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        // May be null — the client renders an empty state, no placeholder
        return new ProfilePictureResponseDto(user.getProfilePicture());
    }

    @Transactional
    public ProfilePictureResponseDto updateProfilePicture(String username, String base64Image) {
        log.info("[PROFILE-PIC] Update for username={}", username);
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        validateProfilePicture(base64Image);

        user.setProfilePicture(base64Image);
        userRepository.save(user);
        log.info("[PROFILE-PIC] Updated ({} base64 chars) for username={}", base64Image.length(), username);
        return new ProfilePictureResponseDto(user.getProfilePicture());
    }

    @Transactional
    public void deleteProfilePicture(String username) {
        log.info("[PROFILE-PIC] Delete for username={}", username);
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setProfilePicture(null);
        userRepository.save(user);
        log.info("[PROFILE-PIC] Deleted for username={}", username);
    }

    private void validateProfilePicture(String base64Image) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Image);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("profilePicture bukan format base64 yang valid");
        }
        if (decoded.length > MAX_PROFILE_PICTURE_BYTES) {
            throw new IllegalArgumentException("profilePicture terlalu besar (maksimal 1MB)");
        }
    }
}
