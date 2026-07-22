package com.bejo.commerce.domain;

/**
 * Well-known {@code bejo_history.acquirer} values. Acquirer is otherwise free-form
 * (it can name any payment channel), but these two are used as defaults:
 *
 * <ul>
 *   <li>{@link #CASH} — default acquirer for a {@link TransactionType#MANUAL} entry</li>
 *   <li>{@link #ASTRAPAY} — default acquirer for a QRIS callback ({@link TransactionType#MANAGED})</li>
 * </ul>
 */
public final class Acquirer {

    public static final String CASH = "Cash";
    public static final String ASTRAPAY = "AstraPay";

    private Acquirer() {}
}
