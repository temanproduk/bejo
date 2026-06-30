package com.bejo.service;

import com.bejo.dto.request.ChangePasswordRequest;
import com.bejo.dto.request.UpdateBankRequest;
import com.bejo.dto.request.UpdateBusinessRequest;
import com.bejo.dto.request.UpdateProfileRequest;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void updateProfile(String username, UpdateProfileRequest request) {
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
    public void updateBank(String username, UpdateBankRequest request) {
        log.info("[BANK] username={}", username);

        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setBankName(request.bankName());
        user.setBankNumber(request.bankNumber());
        user.setBankAccountName(request.bankAccountName());

        userRepository.save(user);
        log.info("[BANK] Updated for username={}", username);
    }

    @Transactional
    public void changePassword(String username, ChangePasswordRequest request) {
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

    @Transactional
    public void updateBusiness(String username, UpdateBusinessRequest request) {
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
    public void resetBank(String username) {
        log.info("[BANK RESET] username={}", username);

        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.getInitialBankName() == null) {
            throw new IllegalArgumentException("Data rekening awal tidak tersedia untuk direset");
        }

        user.setBankName(user.getInitialBankName());
        user.setBankNumber(user.getInitialBankNumber());
        user.setBankAccountName(user.getInitialBankAccountName());

        userRepository.save(user);
        log.info("[BANK RESET] Reverted for username={}", username);
    }

    @Transactional
    public void resetBusiness(String username) {
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
    }
}
