package com.bejo.service;

import com.bejo.entity.Session;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private UserRepository userRepository;

    private PushNotificationService service() {
        return new PushNotificationService(sessionRepository, userRepository, new SimpleMeterRegistry());
    }

    @Test
    void unregisteredAndSenderMismatchAreStaleTokens() {
        assertThat(PushNotificationService.isTokenUnregistered(MessagingErrorCode.UNREGISTERED)).isTrue();
        assertThat(PushNotificationService.isTokenUnregistered(MessagingErrorCode.SENDER_ID_MISMATCH)).isTrue();
    }

    @Test
    void transientOrPayloadErrorsAreNotStale() {
        // These must NOT delete the token — the token may still be valid
        assertThat(PushNotificationService.isTokenUnregistered(MessagingErrorCode.INVALID_ARGUMENT)).isFalse();
        assertThat(PushNotificationService.isTokenUnregistered(MessagingErrorCode.UNAVAILABLE)).isFalse();
        assertThat(PushNotificationService.isTokenUnregistered(MessagingErrorCode.INTERNAL)).isFalse();
        assertThat(PushNotificationService.isTokenUnregistered(MessagingErrorCode.QUOTA_EXCEEDED)).isFalse();
        assertThat(PushNotificationService.isTokenUnregistered(null)).isFalse();  // non-messaging FirebaseException
    }

    /**
     * The presence pushes must reach FCM as <b>display notifications</b> (a title/body FCM
     * renders itself), not as the data-only messages the payment flow sends. Nothing else
     * checks the shape of the Message actually handed to FCM, so this drives the real
     * FirebaseMessaging call with a mocked SDK and inspects it.
     */
    @Test
    void notificationPushCarriesTitleAndBodyToFcm() throws Exception {
        Session session = new Session();
        session.setUsername("budi");
        session.setDeviceId("device-1");
        session.setPushToken("token-abc");
        when(sessionRepository.findByUsername("budi")).thenReturn(List.of(session));

        FirebaseMessaging fcm = mock(FirebaseMessaging.class);
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        when(fcm.send(sent.capture())).thenReturn("message-id-1");

        int delivered;
        try (MockedStatic<FirebaseApp> apps = mockStatic(FirebaseApp.class);
             MockedStatic<FirebaseMessaging> messaging = mockStatic(FirebaseMessaging.class)) {
            apps.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));  // "configured"
            messaging.when(FirebaseMessaging::getInstance).thenReturn(fcm);

            delivered = service().sendNotificationToUser("budi",
                    "budi telah absen masuk!",
                    "Pastikan jadwal absen sesuai prosedur. Sukses terus ya!",
                    Map.of("type", "PRESENCE", "presenceId", "7"));
        }

        assertThat(delivered).isEqualTo(1);
        Message message = sent.getValue();
        assertThat(field(message, "token")).isEqualTo("token-abc");

        // A notification block (not just data) — this is what makes the phone display it
        Object notification = field(message, "notification");
        assertThat(notification).isNotNull();
        assertThat(field(notification, "title")).isEqualTo("budi telah absen masuk!");
        assertThat(field(notification, "body")).isEqualTo("Pastikan jadwal absen sesuai prosedur. Sukses terus ya!");

        // …with the routing payload riding along
        assertThat(field(message, "data")).isEqualTo(Map.of("type", "PRESENCE", "presenceId", "7"));
    }

    @Test
    void paymentPushStaysDataOnly() throws Exception {
        Session session = new Session();
        session.setUsername("budi");
        session.setDeviceId("device-1");
        session.setPushToken("token-abc");
        when(sessionRepository.findByUsername("budi")).thenReturn(List.of(session));

        FirebaseMessaging fcm = mock(FirebaseMessaging.class);
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        when(fcm.send(sent.capture())).thenReturn("message-id-2");

        try (MockedStatic<FirebaseApp> apps = mockStatic(FirebaseApp.class);
             MockedStatic<FirebaseMessaging> messaging = mockStatic(FirebaseMessaging.class)) {
            apps.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            messaging.when(FirebaseMessaging::getInstance).thenReturn(fcm);

            service().sendToUser("budi", Map.of("title", "Pembayaran Diterima"));
        }

        // The app builds the payment notification itself — no notification block on the wire
        assertThat(field(sent.getValue(), "notification")).isNull();
        assertThat(field(sent.getValue(), "data")).isEqualTo(Map.of("title", "Pembayaran Diterima"));
    }

    // The Firebase SDK's Message/Notification expose no getters — read the field directly.
    private static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
