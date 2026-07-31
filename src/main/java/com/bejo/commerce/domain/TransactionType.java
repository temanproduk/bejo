package com.bejo.commerce.domain;

/**
 * Canonical set of {@code transactions.type} values.
 *
 * <ul>
 *   <li>{@link #MANUAL} — entered by the user by hand (defaults acquirer to {@link Acquirer#CASH})</li>
 *   <li>{@link #PARSED} — parsed from an external source; requires an explicit acquirer</li>
 *   <li>{@link #MANAGED} — recorded by the Bejo server from a QRIS payment callback; immutable</li>
 * </ul>
 */
public final class TransactionType {

    public static final String MANUAL = "MANUAL";
    public static final String PARSED = "PARSED";
    public static final String MANAGED = "MANAGED";

    private TransactionType() {}
}
