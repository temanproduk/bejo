package com.bejo.commerce.dto;

import java.time.LocalDate;

/**
 * Aggregated transaction totals for the merchant dashboard. Needed because the
 * history endpoint is now paginated, so the client can no longer sum a full page.
 * {@code date} is the reference "today" the periods were computed against (the
 * client-supplied date, or the server date when omitted); {@code month} covers
 * that date's whole calendar month.
 */
public record TransactionSummaryResponseDto(
        LocalDate date,
        PeriodSummary today,
        PeriodSummary yesterday,
        PeriodSummary month
) {
    public record PeriodSummary(long totalAmount, long count) {}
}
