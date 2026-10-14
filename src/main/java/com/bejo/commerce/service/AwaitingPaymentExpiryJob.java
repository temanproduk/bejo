package com.bejo.commerce.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cancels, at the end of each day (WIB), the QRIS sales that were never paid — see
 * {@link TransactionService#expireStaleAwaiting()}.
 *
 * <p>{@code @Lazy(false)}: created at startup so the schedule is registered even with
 * {@code spring.main.lazy-initialization=true}. The service itself is fetched only when the job fires
 * (an {@link ObjectProvider}), so this does not pull the whole transaction stack into startup.
 */
@Component
@Lazy(false)
public class AwaitingPaymentExpiryJob {

    private final ObjectProvider<TransactionService> transactions;

    public AwaitingPaymentExpiryJob(ObjectProvider<TransactionService> transactions) {
        this.transactions = transactions;
    }

    // Just after midnight Asia/Jakarta (= AppTime.ZONE; an annotation needs a constant): everything dated
    // before the day that is starting is cancelled
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Jakarta")
    public void cancelUnpaidAtEndOfDay() {
        transactions.getObject().expireStaleAwaiting();
    }
}
