package com.bejo.exception;

/**
 * Thrown when an authenticated user is not permitted to perform an action (e.g. a
 * non–super-admin trying to manage users, or deleting a super admin). Mapped to HTTP 403
 * by {@link GlobalExceptionHandler}.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
