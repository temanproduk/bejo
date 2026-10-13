package com.bejo.shared.exception;

/**
 * Login refused because the account's email address has not been verified yet. A {@link ForbiddenException}
 * (403) that also carries the address, masked, so the client can say where the code went and move the
 * user on to the verification step instead of showing a generic error. Only ever thrown after the
 * password was right, so it reveals nothing about accounts to someone guessing.
 */
public class EmailNotVerifiedException extends ForbiddenException {

    /** Stable machine-readable value of {@code data.reason} in the response. */
    public static final String REASON = "EMAIL_NOT_VERIFIED";

    private final String maskedEmail;

    public EmailNotVerifiedException(String message, String maskedEmail) {
        super(message);
        this.maskedEmail = maskedEmail;
    }

    public String getMaskedEmail() {
        return maskedEmail;
    }
}
