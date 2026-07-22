package com.bejo.service;

import com.bejo.constant.PushType;
import com.bejo.entity.Session;
import com.bejo.push.PushDeliveryQueue;
import com.bejo.push.PushJobSender;
import com.bejo.push.PushTokenMaintenance;
import com.bejo.push.SimplePushPayload;
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
        PushJobSender sender = new PushJobSender(new PushTokenMaintenance(sessionRepository), new SimpleMeterRegistry());
        // Synchronous queue (the interface is one method) so sends run inline and the wire-format
        // assertions are deterministic.
        PushDeliveryQueue syncQueue = sender::deliver;
        return new PushNotificationService(sessionRepository, userRepository, syncQueue, sender);
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
     * The presence push must reach FCM as a <b>data-only, high-priority</b> message. Both
     * halves matter and neither is visible anywhere else: a {@code notification} block would
     * be drawn by the system tray and never reach {@code onMessageReceived} in the background
     * (so the app could never auto-show its dialog), and normal priority lets FCM hold the
     * message until the device next wakes. So drive the real send with a mocked SDK and look
     * at the Message on the wire.
     */
    @Test
    void presencePushIsDataOnlyAndHighPriority() throws Exception {
        Session session = new Session();
        session.setUsername("budi");
        session.setDeviceId("device-1");
        session.setPushToken("token-abc");
        when(sessionRepository.findByUsername("budi")).thenReturn(List.of(session));

        FirebaseMessaging fcm = mock(FirebaseMessaging.class);
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        when(fcm.send(sent.capture())).thenReturn("message-id-1");

        Map<String, String> data = Map.of(
                "type", PushType.PRESENCE,
                "title", "budi telah absen masuk!",
                "body", "Pastikan jadwal absen sesuai prosedur. Sukses terus ya!",
                "presenceType", "CLOCK_IN",
                "presenceId", "7");

        int delivered;
        try (MockedStatic<FirebaseApp> apps = mockStatic(FirebaseApp.class);
             MockedStatic<FirebaseMessaging> messaging = mockStatic(FirebaseMessaging.class)) {
            apps.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));  // "configured"
            messaging.when(FirebaseMessaging::getInstance).thenReturn(fcm);

            delivered = service().sendToUser("budi", new SimplePushPayload(
                    PushType.PRESENCE,
                    "budi telah absen masuk!",
                    "Pastikan jadwal absen sesuai prosedur. Sukses terus ya!",
                    Map.of("presenceType", "CLOCK_IN", "presenceId", "7"),
                    true));   // urgent → HIGH priority
        }

        assertThat(delivered).isEqualTo(1);
        Message message = sent.getValue();
        assertThat(field(message, "token")).isEqualTo("token-abc");

        // No notification block — the app must get onMessageReceived and render it itself
        assertThat(field(message, "notification")).isNull();
        assertThat(field(message, "data")).isEqualTo(data);

        // …and FCM is told to deliver it now, not whenever the device next wakes
        Object android = field(message, "androidConfig");
        assertThat(android).isNotNull();
        assertThat(String.valueOf(field(android, "priority"))).isEqualToIgnoringCase("high");
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

            service().sendToUser("budi", new SimplePushPayload(
                    PushType.TRANSACTION, "Pembayaran Diterima", null, Map.of()));
        }

        // The app builds the payment notification itself — no notification block on the wire
        assertThat(field(sent.getValue(), "notification")).isNull();
        assertThat(field(sent.getValue(), "data"))
                .isEqualTo(Map.of("type", PushType.TRANSACTION, "title", "Pembayaran Diterima"));
    }

    // The Firebase SDK's Message/Notification expose no getters — read the field directly.
    private static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
