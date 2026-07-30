package com.bejo.core.service;

import com.bejo.shared.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottleServiceTest {

    private LoginThrottleService throttle;

    @BeforeEach
    void setUp() {
        throttle = new LoginThrottleService();
        ReflectionTestUtils.setField(throttle, "maxFailures", 3);
        ReflectionTestUtils.setField(throttle, "lockoutMinutes", 15L);
    }

    @Test
    void allowsAttemptsUpToTheBudget() {
        for (int i = 0; i < 2; i++) {
            assertThatCode(() -> throttle.assertNotLocked("alice")).doesNotThrowAnyException();
            throttle.recordFailure("alice");
        }
        assertThatCode(() -> throttle.assertNotLocked("alice")).doesNotThrowAnyException();
    }

    @Test
    void locksOutAfterTheBudgetIsSpent() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("alice");
        }

        assertThatThrownBy(() -> throttle.assertNotLocked("alice"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("menit");
    }

    @Test
    void aSuccessfulLoginClearsTheFailureHistory() {
        throttle.recordFailure("alice");
        throttle.recordFailure("alice");
        throttle.recordSuccess("alice");

        // Back to a full budget — a user who mistypes then gets it right is never penalised
        throttle.recordFailure("alice");
        throttle.recordFailure("alice");
        assertThatCode(() -> throttle.assertNotLocked("alice")).doesNotThrowAnyException();
    }

    @Test
    void lockoutIsPerAccount() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("alice");
        }

        assertThatThrownBy(() -> throttle.assertNotLocked("alice")).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> throttle.assertNotLocked("bob")).doesNotThrowAnyException();
    }

    @Test
    void usernameCasingCannotMultiplyTheBudget() {
        throttle.recordFailure("alice");
        throttle.recordFailure("ALICE");
        throttle.recordFailure("Alice");

        assertThatThrownBy(() -> throttle.assertNotLocked("alice")).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void purgeDropsIdleAccountsButKeepsActiveLockouts() {
        throttle.recordFailure("idle");
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("locked");
        }
        // "idle" last failed just now, so a purge keeps it; force it into the past
        @SuppressWarnings("unchecked")
        var attempts = (java.util.Map<String, Object>) ReflectionTestUtils.getField(throttle, "attempts");
        ReflectionTestUtils.setField(attempts.get("idle"), "lastFailureAt",
                java.time.LocalDateTime.now().minusHours(2));

        throttle.purgeStale();

        assertThat(attempts).doesNotContainKey("idle");
        assertThatThrownBy(() -> throttle.assertNotLocked("locked")).isInstanceOf(TooManyRequestsException.class);
    }
}
