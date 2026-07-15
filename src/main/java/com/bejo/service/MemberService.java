package com.bejo.service;

import com.bejo.constant.AccountType;
import com.bejo.constant.BusinessRole;
import com.bejo.dto.request.AddMemberRequestDto;
import com.bejo.dto.request.UpdateMemberRequestDto;
import com.bejo.dto.response.MemberResponseDto;
import com.bejo.entity.User;
import com.bejo.exception.ForbiddenException;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manages the users of a business. Only a {@link BusinessRole#SUPER_ADMIN} (the business
 * creator) may add, update, or delete users; any member may list them. Deleting a member
 * soft-deletes it (row kept, {@code deleted_at} set) while releasing its username for
 * reuse. A super admin can never be updated or deleted through here.
 */
@Service
public class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    // Max users a business may have, INCLUDING the super admin (configurable)
    @Value("${app.business.max-users:10}")
    private int maxUsersPerBusiness;

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final BusinessContext businessContext;
    private final PasswordEncoder passwordEncoder;

    public MemberService(UserRepository userRepository,
                         SessionRepository sessionRepository,
                         BusinessContext businessContext,
                         PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.businessContext = businessContext;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<MemberResponseDto> list(String callerUsername) {
        Long businessId = businessContext.requireBusinessId(callerUsername);
        return userRepository.findByBusinessIdAndDeletedAtIsNullOrderByCreatedAtAsc(businessId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public MemberResponseDto add(String callerUsername, AddMemberRequestDto request) {
        User caller = requireSuperAdmin(callerUsername);
        // Cap the number of users per business (super admin counts toward the limit)
        if (userRepository.countByBusinessIdAndDeletedAtIsNull(caller.getBusinessId()) >= maxUsersPerBusiness) {
            throw new IllegalArgumentException("Jumlah pengguna sudah mencapai batas maksimal (" + maxUsersPerBusiness + ")");
        }
        if (userRepository.existsByUsernameAndDeletedAtIsNull(request.username())) {
            throw new IllegalArgumentException("Username sudah digunakan");
        }
        User member = new User();
        member.setUsername(request.username());
        member.setPasswordHash(passwordEncoder.encode(request.password()));
        member.setName(request.name());
        member.setEmail(request.email());
        member.setPhone(request.phone());
        member.setBusinessId(caller.getBusinessId());
        member.setBusinessRole(BusinessRole.MEMBER);
        member.setAccountType(AccountType.MANAGED);
        member.setEmailVerified(false);
        member.setPhoneVerified(false);
        userRepository.save(member);
        log.info("[MEMBER] Added username={} to businessId={} by {}", request.username(), caller.getBusinessId(), callerUsername);
        return toDto(member);
    }

    @Transactional
    public MemberResponseDto update(String callerUsername, Long memberId, UpdateMemberRequestDto request) {
        if (request.isEmpty()) {
            throw new IllegalArgumentException("Tidak ada data yang diubah");
        }
        User caller = requireSuperAdmin(callerUsername);
        User member = requireMember(caller.getBusinessId(), memberId);
        if (member.isSuperAdmin()) {
            throw new ForbiddenException("Super admin tidak dapat diubah");
        }
        if (request.name() != null) member.setName(request.name());
        if (request.email() != null) member.setEmail(request.email());
        if (request.phone() != null) member.setPhone(request.phone());
        if (request.password() != null) member.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(member);
        log.info("[MEMBER] Updated id={} in businessId={} by {}", memberId, caller.getBusinessId(), callerUsername);
        return toDto(member);
    }

    @Transactional
    public void delete(String callerUsername, Long memberId) {
        User caller = requireSuperAdmin(callerUsername);
        User member = requireMember(caller.getBusinessId(), memberId);
        if (member.isSuperAdmin()) {
            throw new ForbiddenException("Super admin tidak dapat dihapus");
        }
        member.setDeletedAt(LocalDateTime.now());
        userRepository.save(member);
        // Drop the member's device sessions so the released username starts clean and the
        // now-orphaned devices stop receiving the business's pushes
        sessionRepository.deleteByUsername(member.getUsername());
        log.info("[MEMBER] Soft-deleted id={} (username '{}' released) in businessId={} by {}",
                memberId, member.getUsername(), caller.getBusinessId(), callerUsername);
    }

    // The caller must exist and be the business's super admin
    private User requireSuperAdmin(String callerUsername) {
        User caller = businessContext.requireUser(callerUsername);
        if (!caller.isSuperAdmin()) {
            throw new ForbiddenException("Hanya super admin yang dapat mengelola pengguna");
        }
        return caller;
    }

    // The target must be an active user of the caller's business
    private User requireMember(Long businessId, Long memberId) {
        return userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(memberId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + memberId));
    }

    private MemberResponseDto toDto(User u) {
        return new MemberResponseDto(u.getId(), u.getUsername(), u.getName(),
                u.getEmail(), u.getPhone(), u.getBusinessRole(), u.getCreatedAt());
    }
}
