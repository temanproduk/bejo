package com.bejo.commerce.domain;

/**
 * Canonical set of {@code transactions.status} values. The QRIS callback maps
 * SNAP status codes onto these (see {@code QrisCallbackService.mapStatus}).
 */
public final class TransactionStatus {

    public static final String SUCCESS = "SUCCESS";
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";
    public static final String REFUNDED = "REFUNDED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";
    public static final String UNKNOWN = "UNKNOWN";

    private TransactionStatus() {}
}
