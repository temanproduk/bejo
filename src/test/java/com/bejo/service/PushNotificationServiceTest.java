package com.bejo.service;

import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PushNotificationServiceTest {

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
}
