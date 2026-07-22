package com.bejo.core.service;

import com.bejo.core.domain.User;
import com.bejo.shared.exception.ForbiddenException;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.core.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * Resolves the business scope for an authenticated request. Business-owned data is keyed
 * on {@code business_id}, but controllers only know the authenticated {@code username}
 * (from the JWT), so services call this to translate one into the other. A user that was
 * soft-deleted after its token was issued resolves to nothing → {@link ResourceNotFoundException}.
 */
@Component
public class BusinessContext {

    private final UserRepository userRepository;

    public BusinessContext(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The authenticated user's business id, or 404 if the user is gone/soft-deleted. */
    public Long requireBusinessId(String username) {
        return userRepository.findBusinessIdByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    /**
     * The authenticated user's business id, but <b>only if they are the business's super
     * admin</b> — used to gate business-wide settings changes (app config, business picture,
     * QRIS image). A plain member gets a {@link ForbiddenException} (403).
     */
    public Long requireSuperAdminBusinessId(String username) {
        User user = requireUser(username);
        if (!user.isSuperAdmin()) {
            throw new ForbiddenException("Hanya super admin yang dapat mengubah pengaturan bisnis");
        }
        return user.getBusinessId();
    }

    /** The authenticated user row (active), or 404. */
    public User requireUser(String username) {
        return userRepository.findByUsernameAndDeletedAtIsNull(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
