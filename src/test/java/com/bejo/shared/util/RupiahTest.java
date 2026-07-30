package com.bejo.shared.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RupiahTest {

    @Test
    void groupsThousandsWithDots() {
        assertThat(Rupiah.format(1_000_000L)).isEqualTo("Rp 1.000.000");
        assertThat(Rupiah.format(25_000L)).isEqualTo("Rp 25.000");
        assertThat(Rupiah.format(1_234_567_890L)).isEqualTo("Rp 1.234.567.890");
    }

    @Test
    void smallAmountsAreNotGrouped() {
        assertThat(Rupiah.format(0L)).isEqualTo("Rp 0");
        assertThat(Rupiah.format(999L)).isEqualTo("Rp 999");
        assertThat(Rupiah.format(1_000L)).isEqualTo("Rp 1.000");
    }

    @Test
    void wholeAmountsAreNeverPaddedWithDecimals() {
        assertThat(Rupiah.format(1_000_000L)).isEqualTo("Rp 1.000.000");
        // Also when the value carries an explicit zero fraction
        assertThat(Rupiah.format(new BigDecimal("1000000.00"))).isEqualTo("Rp 1.000.000");
        assertThat(Rupiah.format(new BigDecimal("100.0"))).isEqualTo("Rp 100");
    }

    @Test
    void decimalsUseACommaAndShowOnlyWhenNonZero() {
        assertThat(Rupiah.format(new BigDecimal("1000000.22"))).isEqualTo("Rp 1.000.000,22");
        assertThat(Rupiah.format(new BigDecimal("0.05"))).isEqualTo("Rp 0,05");
        // A single fractional digit still reads as money, not "Rp 1.000.000,2"
        assertThat(Rupiah.format(new BigDecimal("1000000.20"))).isEqualTo("Rp 1.000.000,20");
    }

    @Test
    void roundsHalfUpToTwoDecimals() {
        assertThat(Rupiah.format(new BigDecimal("10.005"))).isEqualTo("Rp 10,01");
        // Rounds away entirely -> whole, so no ",00" padding
        assertThat(Rupiah.format(new BigDecimal("10.004"))).isEqualTo("Rp 10");
    }

    @Test
    void handlesNegativeAndNull() {
        assertThat(Rupiah.format(-25_000L)).isEqualTo("Rp -25.000");
        assertThat(Rupiah.format(null)).isEqualTo("Rp 0");
    }
}
