package com.bejo.notification;

/**
 * Port for sending a plain-text email. The production implementation speaks SMTP
 * ({@link SmtpEmailSender}); tests bind a recording fake. Lives in {@code notification} like the
 * push infrastructure, so any module may use it without depending on another.
 */
public interface EmailSender {

    /** False when email is not configured, so callers can refuse the feature instead of failing late. */
    boolean isEnabled();

    /** Sends one message. Blocks until the server accepts it or the configured timeout elapses. */
    void send(String to, String subject, String body);
}
