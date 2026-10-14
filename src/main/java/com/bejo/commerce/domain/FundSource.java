package com.bejo.commerce.domain;

/**
 * The "Sumber Dana" a MANUAL transaction is entered with (stored in {@code transactions.source}, sent as
 * {@code issuerName}). The app offers Tunai, Transfer, Kartu Kredit, Kartu Debit, QRIS and Lainnya, but the
 * field stays free text — an older app build, or a transaction entered before the list existed, carries
 * whatever was typed — so only the one value the server acts on is named here.
 */
public final class FundSource {

    /** A QRIS sale is recorded before the customer has paid: it starts {@link TransactionStatus#AWAITING_PAYMENT}. */
    public static final String QRIS = "QRIS";

    private FundSource() {}

    public static boolean isQris(String value) {
        return value != null && QRIS.equalsIgnoreCase(value.trim());
    }
}
