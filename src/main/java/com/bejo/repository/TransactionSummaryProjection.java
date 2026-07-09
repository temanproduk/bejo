package com.bejo.repository;

/**
 * Aggregate over a set of transactions (used by the summary query): the summed
 * {@code amount} and the number of rows. Both are always present — the query
 * COALESCEs the sum to 0, and COUNT is 0 for an empty range.
 */
public interface TransactionSummaryProjection {
    long getTotalAmount();
    long getCount();
}
