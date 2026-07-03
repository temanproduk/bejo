package com.bejo.service;

import com.bejo.dto.request.ChangePasswordRequest;
import com.bejo.dto.request.UpdateBankRequest;
import com.bejo.dto.request.UpdateBusinessRequest;
import com.bejo.dto.request.UpdateProfileRequest;
import com.bejo.dto.response.BankData;
import com.bejo.dto.response.BusinessData;
import com.bejo.entity.UserBank;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.UserBankRepository;
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
    private final UserBankRepository userBankRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       UserBankRepository userBankRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userBankRepository = userBankRepository;
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
    public BusinessData resetBusiness(String username) {
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
        return new BusinessData(user.getBusinessName(), user.getBusinessAddress(), user.getNmid());
    }

    @Transactional
    public void updateBank(String username, UpdateBankRequest request) {
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
    public BankData resetBank(String username) {
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
        return new BankData(bank.getBankName(), bank.getBankNumber(), bank.getBankAccountName());
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
}
