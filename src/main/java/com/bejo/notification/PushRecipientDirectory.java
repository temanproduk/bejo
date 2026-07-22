package com.bejo.notification;

import java.util.Collection;
import java.util.List;

/**
 * A <b>port</b> the notification module owns and another module implements (core, which owns the
 * device sessions). It answers "which devices should this fan-out reach?" as {@link PushRecipient}s,
 * so {@link PushNotificationService} can address a push by username / device / business without the
 * notification module ever importing session or user code. This inverts the dependency: notification
 * defines the interface, core provides the adapter — keeping the module graph acyclic.
 *
 * <p>Implementations return only devices with a non-blank token (a token-less device can't be
 * pushed to), and never {@code null} — an empty list when there is nobody to notify.
 */
public interface PushRecipientDirectory {

    /** Every device the user has a usable push token for. */
    List<PushRecipient> forUser(String username);

    /** The one {@code (username, deviceId)} device, if it has a usable token (else empty). */
    List<PushRecipient> forDevice(String username, String deviceId);

    /** Every device of every active user in the business. */
    List<PushRecipient> forBusiness(Long businessId);

    /** Every device of a specific set of users (the payment callback's computed recipients). */
    List<PushRecipient> forUsernames(Collection<String> usernames);
}
