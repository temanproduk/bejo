package com.bejo.voice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Turns what a speech recogniser heard into a rupiah amount.
 *
 * <p>Recognisers do not agree on how to write a number, and the same sentence can come back as digits
 * ({@code "25000"}, {@code "25.000"}), as words ({@code "dua puluh lima ribu"}), or half and half
 * ({@code "2,5 juta"}, {@code "20 ribu"}), so all three are handled, plus the slang merchants actually say.
 * Only slang whose value is settled is understood (checked against Indonesian sources, see docs/architecture/voice.md):
 * {@code seceng}/{@code ceceng} 1.000, {@code noceng} 2.000, {@code goceng} 5.000, {@code ceban} 10.000,
 * {@code noban} 20.000, {@code gocap}/{@code goban} 50.000, {@code gopek} 500. {@code cepek} is left out on purpose:
 * sources disagree on whether it is Rp100 or Rp100.000, and a guess here is a wrong amount.
 * Words it does not know ("tolong", "catat", "rupiah", "cepek") are ignored, so a short sentence around the number is fine.
 *
 * <p>It never guesses past what was said: nothing numeric, zero, or something too big for the app → empty,
 * and the caller tells the merchant to try again or type it.
 */
public final class IndonesianAmountParser {

    /** Same ceiling as the app's amount field (CalcInput.MAX_AMOUNT). */
    private static final long MAX_AMOUNT = 999_999_999_999_999L;

    private static final Map<String, Integer> DIGITS = Map.ofEntries(
            Map.entry("nol", 0), Map.entry("kosong", 0),
            Map.entry("satu", 1), Map.entry("dua", 2), Map.entry("tiga", 3), Map.entry("empat", 4),
            Map.entry("lima", 5), Map.entry("enam", 6), Map.entry("tujuh", 7), Map.entry("delapan", 8),
            Map.entry("sembilan", 9));

    /** A word that stands for a whole amount on its own, with a count in front of it ("dua ceban" = 20.000). */
    private static final Map<String, Long> SLANG = Map.ofEntries(
            Map.entry("seceng", 1_000L), Map.entry("ceceng", 1_000L), Map.entry("noceng", 2_000L),
            Map.entry("goceng", 5_000L), Map.entry("ceban", 10_000L), Map.entry("noban", 20_000L),
            Map.entry("gocap", 50_000L), Map.entry("goban", 50_000L), Map.entry("gopek", 500L));

    /** "ribu" and friends, including the abbreviations people say or the recogniser writes. */
    private static final Map<String, Long> UNITS = Map.ofEntries(
            Map.entry("ribu", 1_000L), Map.entry("rb", 1_000L), Map.entry("k", 1_000L),
            Map.entry("juta", 1_000_000L), Map.entry("jt", 1_000_000L),
            Map.entry("miliar", 1_000_000_000L), Map.entry("milyar", 1_000_000_000L),
            Map.entry("triliun", 1_000_000_000_000L));

    private static final Pattern GROUPED = Pattern.compile("\\d{1,3}([.,]\\d{3})+");
    private static final Pattern DECIMAL = Pattern.compile("\\d+[.,]\\d+");
    private static final Pattern INTEGER = Pattern.compile("\\d+");
    // A letter next to a digit is a boundary: "20rb", "rp25.000", "1,5jt"
    private static final Pattern LETTER_DIGIT_EDGE = Pattern.compile("(?<=\\p{L})(?=\\d)|(?<=\\d)(?=\\p{L})");

    private IndonesianAmountParser() {}

    public static OptionalLong parse(String heard) {
        if (heard == null || heard.isBlank()) return OptionalLong.empty();
        String text = heard.toLowerCase()
                .replace(",-", " ").replace(".-", " ")
                .replaceAll("[^\\p{L}\\p{N}.,\\s]", " ");

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal group = BigDecimal.ZERO;   // what is being counted below the next unit ("dua ratus lima puluh")
        int lastDigit = 0;                    // a lone digit just read, so "puluh"/"ratus" can multiply it
        long lastUnit = 1;                    // the unit just applied, so a trailing "setengah" is half of it
        boolean sawNumber = false;
        boolean afterKoma = false;            // "dua koma lima": the digits that follow are decimals
        BigDecimal fractionScale = BigDecimal.ONE;

        for (String raw : text.split("\\s+")) {
            for (String token : LETTER_DIGIT_EDGE.split(raw)) {
                token = stripEdges(token);
                if (token.isEmpty()) continue;

                BigDecimal numeric = numeric(token);
                if (numeric != null) {
                    sawNumber = true;
                    group = group.add(numeric);
                    lastDigit = numeric.compareTo(BigDecimal.TEN) < 0 && numeric.scale() <= 0 ? numeric.intValue() : 0;
                    continue;
                }
                Integer digit = DIGITS.get(token);
                if (digit != null) {
                    sawNumber = true;
                    if (afterKoma) {
                        fractionScale = fractionScale.divide(BigDecimal.TEN);
                        group = group.add(BigDecimal.valueOf(digit).multiply(fractionScale));
                        continue;
                    }
                    group = group.add(BigDecimal.valueOf(digit));
                    lastDigit = digit;
                    continue;
                }
                switch (token) {
                    case "koma" -> { afterKoma = true; fractionScale = BigDecimal.ONE; }
                    case "sepuluh" -> { sawNumber = true; group = group.add(BigDecimal.TEN); lastDigit = 0; }
                    case "sebelas" -> { sawNumber = true; group = group.add(BigDecimal.valueOf(11)); lastDigit = 0; }
                    case "belas" -> { group = group.add(BigDecimal.TEN); lastDigit = 0; }
                    case "puluh" -> {
                        group = group.subtract(BigDecimal.valueOf(lastDigit))
                                .add(BigDecimal.valueOf(lastDigit == 0 ? 1 : lastDigit).multiply(BigDecimal.TEN));
                        lastDigit = 0; sawNumber = true;
                    }
                    case "ratus", "seratus" -> {
                        int base = token.equals("seratus") ? 1 : lastDigit;
                        group = group.subtract(BigDecimal.valueOf(token.equals("seratus") ? 0 : lastDigit))
                                .add(BigDecimal.valueOf(base == 0 ? 1 : base).multiply(BigDecimal.valueOf(100)));
                        lastDigit = 0; sawNumber = true;
                    }
                    case "setengah" -> {
                        sawNumber = true;
                        if (group.signum() == 0 && lastUnit > 1 && total.signum() > 0) {
                            total = total.add(BigDecimal.valueOf(lastUnit).divide(BigDecimal.valueOf(2)));   // "dua ribu setengah"
                        } else {
                            group = group.add(new BigDecimal("0.5"));
                        }
                    }
                    case "seribu" -> { total = total.add(BigDecimal.valueOf(1_000)); group = BigDecimal.ZERO; lastDigit = 0; lastUnit = 1_000; sawNumber = true; }
                    case "sejuta" -> { total = total.add(BigDecimal.valueOf(1_000_000)); group = BigDecimal.ZERO; lastDigit = 0; lastUnit = 1_000_000; sawNumber = true; }
                    case "semiliar", "semilyar" -> { total = total.add(BigDecimal.valueOf(1_000_000_000)); group = BigDecimal.ZERO; lastDigit = 0; lastUnit = 1_000_000_000; sawNumber = true; }
                    default -> {
                        Long unit = UNITS.get(token);
                        Long slang = SLANG.get(token);
                        if (unit != null) {
                            // "ribu" with nothing counted before it is one of them ("ribu" = 1.000)
                            BigDecimal count = group.signum() == 0 ? BigDecimal.ONE : group;
                            total = total.add(count.multiply(BigDecimal.valueOf(unit)));
                            group = BigDecimal.ZERO; lastDigit = 0; lastUnit = unit; sawNumber = true; afterKoma = false;
                        } else if (slang != null) {
                            BigDecimal count = group.signum() == 0 ? BigDecimal.ONE : group;
                            total = total.add(count.multiply(BigDecimal.valueOf(slang)));
                            group = BigDecimal.ZERO; lastDigit = 0; lastUnit = slang; sawNumber = true;
                        }
                        // anything else ("tolong", "rupiah", "catat") is just talk
                    }
                }
            }
        }

        if (!sawNumber) return OptionalLong.empty();
        BigDecimal result = total.add(group).setScale(0, RoundingMode.HALF_UP);
        if (result.signum() <= 0 || result.compareTo(BigDecimal.valueOf(MAX_AMOUNT)) > 0) return OptionalLong.empty();
        return OptionalLong.of(result.longValueExact());
    }

    /** A written number: {@code 25000}, {@code 25.000} / {@code 25,000} (thousands), {@code 2,5} (a decimal). */
    private static BigDecimal numeric(String token) {
        if (INTEGER.matcher(token).matches()) return new BigDecimal(token);
        if (GROUPED.matcher(token).matches()) return new BigDecimal(token.replaceAll("[.,]", ""));
        if (DECIMAL.matcher(token).matches()) return new BigDecimal(token.replace(',', '.'));
        return null;
    }

    private static String stripEdges(String s) {
        int start = 0, end = s.length();
        while (start < end && (s.charAt(start) == '.' || s.charAt(start) == ',')) start++;
        while (end > start && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ',')) end--;
        return s.substring(start, end);
    }
}
