package com.bejo.commerce.domain;

/**
 * Canonical set of {@code transactions.status} values. The QRIS callback maps
 * SNAP status codes onto these (see {@code QrisCallbackService.mapStatus}).
 */
public final class TransactionStatus {

    public static final String SUCCESS = "SUCCESS";
    /**
     * Recorded by hand as a QRIS sale (see {@link FundSource#QRIS}) and not yet paid. It is not income
     * until it settles to {@link #SUCCESS} — by a matching payment from the app or by someone marking
     * it paid — so the totals leave it out. Only a MANUAL transaction is ever in this state.
     */
    public static final String AWAITING_PAYMENT = "AWAITING_PAYMENT";
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";
    public static final String REFUNDED = "REFUNDED";
    /**
     * Not paid. A QRIS sale still {@link #AWAITING_PAYMENT} when its day ends is cancelled by the server
     * (and its stock returned); the QRIS callback also reports it. Either way it is not income.
     */
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";
    public static final String UNKNOWN = "UNKNOWN";

    private TransactionStatus() {}
}
