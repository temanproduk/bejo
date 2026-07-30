package com.bejo.shared.exception;

/**
 * The caller has been rate-limited (HTTP 429). Thrown by throttles that protect a
 * credential-checking endpoint; the message is user-facing and says how long to wait.
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
