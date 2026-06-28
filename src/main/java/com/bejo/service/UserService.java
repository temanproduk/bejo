package com.bejo.service;

import com.bejo.dto.request.ChangePasswordRequest;
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

        if (request.name() != null)  user.setName(request.name());
        user.setEmail(request.email());
        user.setPhone(request.phone());

        userRepository.save(user);
        log.info("[PROFILE] Updated for username={}", username);
    }

    @Transactional
    public void changePassword(String username, ChangePasswordRequest request) {
        log.info("[PASSWORD] username={}", username);

        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Password saat ini tidak sesuai");
        }

        String newPw = request.newPassword();
        if (newPw == null || newPw.length() < 8) {
            throw new IllegalArgumentException("Password baru minimal 8 karakter");
        }
        if (!newPw.matches("^(?=.*[a-zA-Z])(?=.*\\d).+$")) {
            throw new IllegalArgumentException("Password baru harus mengandung minimal 1 huruf dan 1 angka");
        }

        user.setPasswordHash(passwordEncoder.encode(newPw));
        userRepository.save(user);
        log.info("[PASSWORD] Changed for username={}", username);
    }
}
