package com.bejo.notification;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test double for {@link EmailSender}: records every message instead of speaking SMTP, so the
 * functional tests can read the reset or verification code out of the "email" and finish the flow. Bound under the
 * {@code test} profile (where {@link SmtpEmailSender} is not active).
 */
@Service
@Profile("test")
public class InMemoryEmailSender implements EmailSender {

    public record Sent(String to, String subject, String body) {}

    private static final Pattern CODE = Pattern.compile("Kode (?:reset|verifikasi) Anda: (\\d{6})");

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private volatile boolean enabled = true;
    private volatile boolean failing = false;

    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void send(String to, String subject, String body) {
        if (failing) throw new EmailDeliveryException("simulated SMTP outage", null);
        sent.add(new Sent(to, subject, body));
    }

    public List<Sent> sentTo(String address) {
        return sent.stream().filter(s -> s.to().equalsIgnoreCase(address)).toList();
    }

    /** The six-digit code in the most recent message to {@code address}, or null if none. */
    public String lastCodeTo(String address) {
        List<Sent> mine = sentTo(address);
        if (mine.isEmpty()) return null;
        Matcher m = CODE.matcher(mine.get(mine.size() - 1).body());
        return m.find() ? m.group(1) : null;
    }

    public int totalSent() { return sent.size(); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setFailing(boolean failing) { this.failing = failing; }
}
