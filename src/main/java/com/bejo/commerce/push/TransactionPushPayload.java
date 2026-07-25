package com.bejo.commerce.push;

import com.bejo.notification.PushType;
import com.bejo.commerce.domain.Transaction;
import com.bejo.notification.AbstractPushPayload;

import java.util.Map;

/**
 * A payment landed — sent to every device of every user in the business. <b>Urgent</b>: delivered
 * data-only at Android priority HIGH so the app wakes at once and can run its payment announcement
 * even when backgrounded / in Doze, rather than FCM batching it until the device next wakes.
 */
public final class TransactionPushPayload extends AbstractPushPayload {

    private final Transaction transaction;
    private final String merchantName;

    public TransactionPushPayload(Transaction transaction, String merchantName, String title, String body) {
        super(PushType.TRANSACTION, title, body);
        this.transaction = transaction;
        this.merchantName = merchantName;
    }

    @Override
    public boolean urgent() {
        return true;
    }

    @Override
    protected void putExtra(Map<String, String> data) {
        data.put("transactionId", transaction.getTransactionId());
        putIfPresent(data, "transactionDate", transaction.getTransactionDate());
        data.put("amount", String.valueOf(transaction.getAmount()));
        data.put("status", transaction.getStatus());
        putIfPresent(data, "issuerName", transaction.getSource());
        putIfPresent(data, "merchantName", merchantName);
        putIfPresent(data, "merchantPan", transaction.getMerchantPan());
        putIfPresent(data, "customerPan", transaction.getCustomerPan());
    }
}
