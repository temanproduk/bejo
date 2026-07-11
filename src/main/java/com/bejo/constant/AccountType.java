package com.bejo.constant;

/**
 * Canonical set of {@code users.account_type} values — <b>how the QRIS merchant account
 * was provisioned</b>. This is a distinct axis from {@link BusinessRole} (which is the
 * user's power <i>within</i> its business): {@code account_type} is about the merchant's
 * relationship with the payment provider.
 *
 * <ul>
 *   <li>{@link #MANAGED} — Bejo registered this merchant with the QRIS payment provider,
 *       so it can receive inbound QRIS payment callbacks (seed data / internal provisioning)</li>
 *   <li>{@link #SELF_SERVICE} — the merchant self-registered through the public API; QRIS is
 *       optional (no provider registration, so no inbound callbacks by default)</li>
 * </ul>
 */
public final class AccountType {

    public static final String MANAGED = "MANAGED";
    public static final String SELF_SERVICE = "SELF_SERVICE";

    private AccountType() {}
}
