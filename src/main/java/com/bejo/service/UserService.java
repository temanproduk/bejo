package com.bejo.service;

import com.bejo.constant.AccountType;
import com.bejo.constant.BusinessRole;
import com.bejo.dto.request.ChangePasswordRequestDto;
import com.bejo.dto.request.RegisterRequestDto;
import com.bejo.dto.request.UpdateBankRequestDto;
import com.bejo.dto.request.UpdateBusinessRequestDto;
import com.bejo.dto.request.UpdateProfileRequestDto;
import com.bejo.dto.response.BankResponseDto;
import com.bejo.dto.response.BusinessResponseDto;
import com.bejo.dto.response.MeResponseDto;
import com.bejo.dto.response.ProfilePictureResponseDto;
import com.bejo.entity.Business;
import com.bejo.entity.BusinessBank;
import com.bejo.entity.User;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.BusinessBankRepository;
import com.bejo.repository.BusinessRepository;
import com.bejo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;

/**
 * User + business account management. Public registration creates a new {@link Business}
 * and its first user (the {@link BusinessRole#SUPER_ADMIN} creator). Per-user operations
 * (profile, password) are keyed on the username; business-level operations (business info,
 * bank, the shared business picture) resolve the caller's {@code business_id} and are
 * shared by every member. Authentication (login/logout/session) lives in {@link AuthService};
 * member management in {@link MemberService}.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    // 1 MB — the maximum size of the decoded business picture
    private static final int MAX_PICTURE_BYTES = 1_048_576;

    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final BusinessBankRepository businessBankRepository;
    private final BusinessContext businessContext;
    private final AppSettingsService appSettingsService;
    private final BusinessOptions businessOptions;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       BusinessRepository businessRepository,
                       BusinessBankRepository businessBankRepository,
                       BusinessContext businessContext,
                       AppSettingsService appSettingsService,
                       BusinessOptions businessOptions,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.businessBankRepository = businessBankRepository;
        this.businessContext = businessContext;
        this.appSettingsService = appSettingsService;
        this.businessOptions = businessOptions;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * The authenticated user's own account bundle (profile + role + shared business/bank/
     * picture/app settings) — the same shape as the login response, so the client can
     * refresh its state without re-authenticating.
     */
    @Transactional(readOnly = true)
    public MeResponseDto getMe(String username) {
        User user = businessContext.requireUser(username);
        Business business = businessRepository.findById(user.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        BusinessBank bank = businessBankRepository.findByBusinessId(user.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Bank data not found"));
        return new MeResponseDto(
                user.getId(), user.getUsername(), user.getAccountType(), user.getName(),
                user.getEmail(), user.isEmailVerified(), user.getPhone(), user.isPhoneVerified(),
                user.getBusinessRole(), business.getName(), business.getAddress(), business.getNmid(),
                bank.getBankName(), bank.getBankNumber(), bank.getBankAccountName(),
                business.getPicture(), appSettingsService.getSettings(username));
    }

    /**
     * Self-service registration: creates a new business and its first user as the
     * {@link BusinessRole#SUPER_ADMIN}. QRIS is optional (no {@code business_qris} row is
     * created — the user may upload one later).
     */
    @Transactional
    public void register(RegisterRequestDto request) {
        log.info("[REGISTER] username={}", request.username());
        if (userRepository.existsByUsernameAndDeletedAtIsNull(request.username())) {
            throw new IllegalArgumentException("Username sudah digunakan");
        }

        Business business = new Business();
        business.setName(request.businessName());
        business.setAddress(request.businessAddress());
        business.setNmid(request.nmid());
        business.setInitialName(request.businessName());
        business.setInitialAddress(request.businessAddress());
        business.setInitialNmid(request.nmid());
        // Set once at registration, immutable afterwards. size/industry validated + defaulted
        // against the configurable option lists; store photo is optional (same spec as picture).
        business.setSize(businessOptions.resolveSize(request.size()));
        business.setIndustry(businessOptions.resolveIndustry(request.industry()));
        if (request.storePhoto() != null) {
            validateImage(request.storePhoto(), "storePhoto");
            business.setStorePhoto(request.storePhoto());
        }
        businessRepository.save(business);

        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setBusinessId(business.getId());
        user.setBusinessRole(BusinessRole.SUPER_ADMIN);
        user.setAccountType(AccountType.SELF_SERVICE);
        user.setEmailVerified(false);
        user.setPhoneVerified(false);
        userRepository.save(user);

        String bankName        = request.bankName()        != null ? request.bankName()        : "";
        String bankNumber      = request.bankNumber()      != null ? request.bankNumber()      : "";
        String bankAccountName = request.bankAccountName() != null ? request.bankAccountName() : "";
        BusinessBank bank = new BusinessBank();
        bank.setBusinessId(business.getId());
        bank.setBankName(bankName);
        bank.setBankNumber(bankNumber);
        bank.setBankAccountName(bankAccountName);
        bank.setInitialBankName(bankName);
        bank.setInitialBankNumber(bankNumber);
        bank.setInitialBankAccountName(bankAccountName);
        businessBankRepository.save(bank);

        log.info("[REGISTER] Created business id={} + super admin username={}", business.getId(), request.username());
    }

    @Transactional
    public void updateProfile(String username, UpdateProfileRequestDto request) {
        log.info("[PROFILE] username={}", username);
        User user = businessContext.requireUser(username);
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        userRepository.save(user);
        log.info("[PROFILE] Updated for username={}", username);
    }

    @Transactional
    public void updateBusiness(String username, UpdateBusinessRequestDto request) {
        Business business = requireBusiness(username);
        business.setName(request.businessName());
        business.setAddress(request.businessAddress());
        business.setNmid(request.nmid());
        businessRepository.save(business);
        log.info("[BUSINESS] Updated businessId={} by username={}", business.getId(), username);
    }

    @Transactional
    public BusinessResponseDto resetBusiness(String username) {
        Business business = requireBusiness(username);
        if (business.getInitialName() == null) {
            throw new IllegalArgumentException("Data bisnis awal tidak tersedia untuk direset");
        }
        business.setName(business.getInitialName());
        business.setAddress(business.getInitialAddress());
        business.setNmid(business.getInitialNmid());
        businessRepository.save(business);
        log.info("[BUSINESS RESET] Reverted businessId={} by username={}", business.getId(), username);
        return new BusinessResponseDto(business.getName(), business.getAddress(), business.getNmid());
    }

    @Transactional
    public void updateBank(String username, UpdateBankRequestDto request) {
        BusinessBank bank = requireBank(username);
        bank.setBankName(request.bankName());
        bank.setBankNumber(request.bankNumber());
        bank.setBankAccountName(request.bankAccountName());
        businessBankRepository.save(bank);
        log.info("[BANK] Updated businessId={} by username={}", bank.getBusinessId(), username);
    }

    @Transactional
    public BankResponseDto resetBank(String username) {
        BusinessBank bank = requireBank(username);
        if (bank.getInitialBankName() == null) {
            throw new IllegalArgumentException("Data rekening awal tidak tersedia untuk direset");
        }
        bank.setBankName(bank.getInitialBankName());
        bank.setBankNumber(bank.getInitialBankNumber());
        bank.setBankAccountName(bank.getInitialBankAccountName());
        businessBankRepository.save(bank);
        log.info("[BANK RESET] Reverted businessId={} by username={}", bank.getBusinessId(), username);
        return new BankResponseDto(bank.getBankName(), bank.getBankNumber(), bank.getBankAccountName());
    }

    @Transactional
    public void changePassword(String username, ChangePasswordRequestDto request) {
        log.info("[PASSWORD] username={}", username);
        User user = businessContext.requireUser(username);
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Password saat ini tidak sesuai");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        log.info("[PASSWORD] Changed for username={}", username);
    }

    // ── Business picture (shared by all members; endpoints kept under /me for the client) ──

    @Transactional(readOnly = true)
    public ProfilePictureResponseDto getProfilePicture(String username) {
        Business business = requireBusiness(username);
        return new ProfilePictureResponseDto(business.getPicture());
    }

    @Transactional
    public ProfilePictureResponseDto updateProfilePicture(String username, String base64Image) {
        Business business = requireBusinessAsSuperAdmin(username);  // super-admin only
        validateImage(base64Image, "profilePicture");
        business.setPicture(base64Image);
        businessRepository.save(business);
        log.info("[PICTURE] Updated ({} base64 chars) for businessId={}", base64Image.length(), business.getId());
        return new ProfilePictureResponseDto(business.getPicture());
    }

    @Transactional
    public void deleteProfilePicture(String username) {
        Business business = requireBusinessAsSuperAdmin(username);  // super-admin only
        business.setPicture(null);
        businessRepository.save(business);
        log.info("[PICTURE] Deleted for businessId={}", business.getId());
    }

    private Business requireBusiness(String username) {
        Long businessId = businessContext.requireBusinessId(username);
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
    }

    // Like requireBusiness, but only the business's super admin may proceed (403 otherwise)
    private Business requireBusinessAsSuperAdmin(String username) {
        Long businessId = businessContext.requireSuperAdminBusinessId(username);
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
    }

    private BusinessBank requireBank(String username) {
        Long businessId = businessContext.requireBusinessId(username);
        return businessBankRepository.findByBusinessId(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Bank data not found"));
    }

    // Validates a base64 image field (business picture / store photo) — same 1 MB limit.
    private void validateImage(String base64, String field) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Format gambar tidak valid");
        }
        if (decoded.length > MAX_PICTURE_BYTES) {
            throw new IllegalArgumentException("Ukuran gambar maksimal 1MB");
        }
    }
}
