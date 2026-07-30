package com.bejo.shared.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Renders an amount in Indonesian currency format — {@code Rp 1.000.000,22}: {@code .} groups
 * thousands, {@code ,} separates the decimals.
 *
 * <p>Decimals appear <b>only when there is a fractional part</b>; a whole amount is never padded
 * to {@code ,00}. When there is one it is shown to two places ({@code Rp 1.000.000,20}), since a
 * single trailing digit reads wrong for money.
 *
 * <p>Note that {@code bejo_history.amount} is a {@code BIGINT} of whole rupiah — the QRIS callback
 * already rounds the provider's {@code "100.00"} to {@code 100} — so the decimal branch is
 * unreachable for today's transaction pushes. It is implemented and tested so the helper stays
 * correct if a decimal-bearing amount is ever formatted through it.
 *
 * <p>A new {@link DecimalFormat} is built per call because it is not thread-safe, and these calls
 * are per push fan-out rather than per device.
 */
public final class Rupiah {

    private Rupiah() {}

    /** Formats whole rupiah, e.g. {@code 1000000} -> {@code "Rp 1.000.000"}. */
    public static String format(long amount) {
        return format(BigDecimal.valueOf(amount));
    }

    /** Formats an amount, e.g. {@code 1000000.22} -> {@code "Rp 1.000.000,22"}. */
    public static String format(BigDecimal amount) {
        if (amount == null) {
            return "Rp 0";
        }
        // Round to the two decimals we'd display FIRST, then decide whether any remain: an amount
        // that only rounds away (10.004 -> 10.00) is whole and must not be padded to ",00".
        // stripTrailingZeros also makes an explicit 1000000.00 count as whole.
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        boolean hasFraction = value.scale() > 0;

        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');

        DecimalFormat format = new DecimalFormat(hasFraction ? "#,##0.00" : "#,##0", symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return "Rp " + format.format(value);
    }
}
