package com.bejo.notification;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Address;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The real {@link SmtpEmailSender} against an in-process SMTP server (GreenMail): that it
 * authenticates, builds a correct message, and reports failures. What it cannot prove is Zoho's own
 * behaviour (data-centre host, application password, From-address policy) — that needs real credentials.
 */
class SmtpEmailSenderTest {

    @RegisterExtension
    static final GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("noreply@example.com", "app-password"));

    @SuppressWarnings("unchecked")
    private SmtpEmailSender sender(String password, String from) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost("127.0.0.1");
        impl.setPort(smtp.getSmtp().getPort());
        impl.setUsername("noreply@example.com");
        impl.setPassword(password);
        Properties props = impl.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "false");   // GreenMail's plain SMTP; Zoho uses STARTTLS or SSL
        props.put("mail.smtp.connectiontimeout", "3000");
        props.put("mail.smtp.timeout", "3000");

        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(impl);
        SmtpEmailSender s = new SmtpEmailSender(provider);
        ReflectionTestUtils.setField(s, "host", "127.0.0.1");
        ReflectionTestUtils.setField(s, "username", "noreply@example.com");
        ReflectionTestUtils.setField(s, "password", password);
        ReflectionTestUtils.setField(s, "from", from);
        ReflectionTestUtils.setField(s, "fromName", "Bejo");
        return s;
    }

    @Test
    void deliversAUtf8MessageFromTheConfiguredSenderName() throws Exception {
        SmtpEmailSender sender = sender("app-password", "");

        sender.send("budi@example.com", "Kode reset kata sandi Bejo", "Halo Budi,\nKode reset Anda: 123456\nCafé — ok");

        MimeMessage[] received = smtp.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage m = received[0];
        assertThat(m.getSubject()).isEqualTo("Kode reset kata sandi Bejo");
        assertThat(m.getAllRecipients()).extracting(Address::toString).containsExactly("budi@example.com");
        InternetAddress from = (InternetAddress) m.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("noreply@example.com");   // defaults to the mailbox (Zoho requires it)
        assertThat(from.getPersonal()).isEqualTo("Bejo");
        assertThat(m.getContent().toString()).contains("Kode reset Anda: 123456").contains("Café — ok");
    }

    @Test
    void anExplicitFromAddressIsUsedWhenGiven() throws Exception {
        sender("app-password", "alias@example.com").send("a@example.com", "s", "b");

        MimeMessage m = smtp.getReceivedMessages()[smtp.getReceivedMessages().length - 1];
        assertThat(((InternetAddress) m.getFrom()[0]).getAddress()).isEqualTo("alias@example.com");
    }

    @Test
    void aWrongPasswordIsAnEmailDeliveryExceptionThatDoesNotLeakIt() {
        SmtpEmailSender sender = sender("wrong-password", "");

        assertThatThrownBy(() -> sender.send("budi@example.com", "s", "b"))
                .isInstanceOf(EmailDeliveryException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("wrong-password"));
    }

    @Test
    void isDisabledUntilHostUserAndPasswordAreAllSet() {
        SmtpEmailSender s = sender("app-password", "");
        assertThat(s.isEnabled()).isTrue();

        ReflectionTestUtils.setField(s, "password", "");
        assertThat(s.isEnabled()).isFalse();
        ReflectionTestUtils.setField(s, "password", "x");
        ReflectionTestUtils.setField(s, "host", "");
        assertThat(s.isEnabled()).isFalse();
        assertThatThrownBy(() -> s.send("a@example.com", "s", "b")).isInstanceOf(IllegalStateException.class);
    }
}
