package com.bejo.shared.exception;

/** A feature that is switched off by configuration (e.g. email is not set up). Maps to 503. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
