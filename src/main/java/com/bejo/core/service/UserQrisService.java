package com.bejo.core.service;

import com.bejo.core.dto.SetUserQrisRequestDto;
import com.bejo.core.dto.UserQrisResponseDto;
import com.bejo.core.domain.User;
import com.bejo.core.domain.UserQris;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.core.repository.UserQrisRepository;
import com.bejo.core.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Per-user QRIS management, driven <b>only by the business's main account</b> (super admin). The
 * main account may give any user in its business their own QRIS image + terminal id, so a payment
 * callback (which carries the terminal id) rings only that user; a user with no assignment falls
 * back to the shared {@link BusinessQris} and is rung for every callback of the merchant.
 *
 * <p>Setting a per-user QRIS is a team feature — gated on team management being enabled (and super
 * admin). Reading/removing needs only super admin.
 */
@Service
public class UserQrisService {

    private static final Logger log = LoggerFactory.getLogger(UserQrisService.class);

    private final UserQrisRepository userQrisRepository;
    private final UserRepository userRepository;
    private final BusinessContext businessContext;
    private final AccountSettingsService accountSettingsService;

    public UserQrisService(UserQrisRepository userQrisRepository,
                           UserRepository userRepository,
                           BusinessContext businessContext,
                           AccountSettingsService accountSettingsService) {
        this.userQrisRepository = userQrisRepository;
        this.userRepository = userRepository;
        this.businessContext = businessContext;
        this.accountSettingsService = accountSettingsService;
    }

    /** Assign (or update) one user's own QRIS + terminal id. Super admin + team management only. */
    @Transactional
    public UserQrisResponseDto set(String callerUsername, Long targetUserId, SetUserQrisRequestDto request) {
        Long businessId = businessContext.requireSuperAdminBusinessId(callerUsername);
        accountSettingsService.requireTeamManagementEnabled(businessId);
        User target = requireActiveUserInBusiness(businessId, targetUserId);
        String targetUsername = target.getUsername();
        validateBase64Image(request.qrisImage());

        String terminalId = request.terminalId().trim();
        // Friendly duplicate-terminal check (the DB partial-unique index is the backstop)
        userQrisRepository.findByBusinessId(businessId).stream()
                .filter(q -> !q.getUsername().equals(targetUsername) && q.getTerminalId().equals(terminalId))
                .findAny()
                .ifPresent(q -> { throw new IllegalArgumentException("Terminal ID sudah digunakan"); });

        UserQris qris = userQrisRepository.findByBusinessIdAndUsername(businessId, targetUsername)
                .orElseGet(() -> {
                    UserQris created = new UserQris();
                    created.setBusinessId(businessId);
                    created.setUsername(targetUsername);
                    return created;
                });
        qris.setTerminalId(terminalId);
        qris.setQrisImage(request.qrisImage());
        userQrisRepository.save(qris);
        log.info("[USER-QRIS] Set QRIS for userId={} username={} terminalId={} in businessId={} by {}",
                targetUserId, targetUsername, terminalId, businessId, callerUsername);
        return toDto(qris, target.getId(), true);
    }

    /** List the business's per-user QRIS assignments (images omitted). Super admin only. */
    @Transactional(readOnly = true)
    public List<UserQrisResponseDto> list(String callerUsername) {
        Long businessId = businessContext.requireSuperAdminBusinessId(callerUsername);
        // Resolve username -> id for the business's active users (small set) so each row carries userId.
        Map<String, Long> idByUsername = userRepository.findByBusinessIdAndDeletedAtIsNullOrderByCreatedAtAsc(businessId)
                .stream().collect(Collectors.toMap(User::getUsername, User::getId));
        return userQrisRepository.findByBusinessId(businessId).stream()
                .map(q -> toDto(q, idByUsername.get(q.getUsername()), false))
                .toList();
    }

    /** One user's QRIS assignment, with the image. Super admin only. */
    @Transactional(readOnly = true)
    public UserQrisResponseDto get(String callerUsername, Long targetUserId) {
        Long businessId = businessContext.requireSuperAdminBusinessId(callerUsername);
        User target = requireActiveUserInBusiness(businessId, targetUserId);
        UserQris qris = userQrisRepository.findByBusinessIdAndUsername(businessId, target.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("QRIS pengguna tidak ditemukan: " + targetUserId));
        return toDto(qris, target.getId(), true);
    }

    /** Remove a user's QRIS assignment — they revert to the shared business QRIS. Super admin only. */
    @Transactional
    public void delete(String callerUsername, Long targetUserId) {
        Long businessId = businessContext.requireSuperAdminBusinessId(callerUsername);
        User target = requireActiveUserInBusiness(businessId, targetUserId);
        if (userQrisRepository.findByBusinessIdAndUsername(businessId, target.getUsername()).isEmpty()) {
            throw new ResourceNotFoundException("QRIS pengguna tidak ditemukan: " + targetUserId);
        }
        userQrisRepository.deleteByBusinessIdAndUsername(businessId, target.getUsername());
        log.info("[USER-QRIS] Removed QRIS for userId={} username={} in businessId={} by {}",
                targetUserId, target.getUsername(), businessId, callerUsername);
    }

    // The target must be an active user of the caller's own business (keyed by user id, like members).
    private User requireActiveUserInBusiness(Long businessId, Long userId) {
        return userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .orElseThrow(() -> new IllegalArgumentException("Pengguna tidak ditemukan: " + userId));
    }

    private void validateBase64Image(String value) {
        try {
            Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Format gambar QRIS tidak valid");
        }
    }

    private UserQrisResponseDto toDto(UserQris q, Long userId, boolean withImage) {
        return new UserQrisResponseDto(userId, q.getUsername(), q.getTerminalId(),
                withImage ? q.getQrisImage() : null, q.getCreatedAt(), q.getUpdatedAt());
    }
}
