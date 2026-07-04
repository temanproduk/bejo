package com.bejo.constant;

/**
 * Canonical set of {@code users.account_type} values.
 *
 * <ul>
 *   <li>{@link #MANAGED} — created internally/by the system (seed data, internal
 *       registration API); provisioned with a managed QRIS row</li>
 *   <li>{@link #SELF_SERVICE} — self-registered through the public registration API;
 *       QRIS is optional (no default image is created at registration)</li>
 * </ul>
 */
public final class AccountType {

    public static final String MANAGED = "MANAGED";
    public static final String SELF_SERVICE = "SELF_SERVICE";

    private AccountType() {}
}
