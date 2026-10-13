package com.bejo.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;

/**
 * SMTP implementation (Zoho Mail in production). Configured by {@code spring.mail.*}
 * ({@code MAIL_HOST}/{@code MAIL_PORT}/{@code MAIL_USERNAME}/{@code MAIL_PASSWORD}); email counts as
 * enabled only when host, username and password are all set.
 *
 * <p>Zoho requires the From address to be the authenticated mailbox or one of its aliases, so
 * {@code app.mail.from} defaults to the username. With two-factor authentication on the Zoho account,
 * the password must be an application-specific password. Connection/read/write timeouts are set in
 * {@code application.yml} so a slow mail server cannot hold a request thread for long.
 *
 * <p>Not active under the {@code test} profile — the functional tests bind a recording fake.
 */
@Service
@Profile("!test")
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final ObjectProvider<JavaMailSender> mailSender;

    @Value("${spring.mail.host:}")
    private String host;
    @Value("${spring.mail.username:}")
    private String username;
    @Value("${spring.mail.password:}")
    private String password;
    @Value("${app.mail.from:}")
    private String from;
    @Value("${app.mail.from-name:Bejo}")
    private String fromName;

    public SmtpEmailSender(ObjectProvider<JavaMailSender> mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.hasText(host) && StringUtils.hasText(username) && StringUtils.hasText(password)
                && mailSender.getIfAvailable() != null;
    }

    @Override
    public void send(String to, String subject, String body) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || !isEnabled()) {
            throw new IllegalStateException("Email belum dikonfigurasi");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(StringUtils.hasText(from) ? from : username, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
            log.info("[EMAIL] Sent \"{}\" via {}", subject, host);
        } catch (MessagingException | UnsupportedEncodingException | org.springframework.mail.MailException e) {
            // e.getMessage() carries the server's reply, never the credentials
            throw new EmailDeliveryException("Gagal mengirim email: " + e.getMessage(), e);
        }
    }
}
