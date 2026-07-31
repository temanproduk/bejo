package com.bejo.core.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the merchant identity out of a QRIS code (EMV TLV payload).
 *
 * <p>The app shows the merchant name and NMID above the QR, and those must describe the QR
 * actually on screen — a sub-account with its own QRIS has a different merchant identity from the
 * business's shared one. So both are taken from the code itself at upload time rather than from
 * manually-entered business info.
 *
 * <ul>
 *   <li><b>Merchant name</b> — top-level tag {@code 59}.</li>
 *   <li><b>NMID</b> (National Merchant ID) — sub-tag {@code 02} of the QRIS domestic template,
 *       the merchant-account tag (26–51) whose sub-tag {@code 00} is {@code ID.CO.QRIS.WWW}.
 *       Scanning for that GUID rather than assuming tag 51 keeps it working for an acquirer that
 *       orders its templates differently.</li>
 *   <li><b>Terminal label</b> — sub-tag {@code 07} of tag {@code 62} (Additional Data Field).
 *       Identifies which terminal the QR belongs to, which is what an inbound payment callback
 *       routes on. Many codes omit it, in which case it is null.</li>
 *   <li><b>Acquirer</b> — the GUID of the <em>other</em> merchant-account template, i.e. the
 *       first one that is not the domestic QRIS switch, e.g. {@code ID.CO.ASTRAPAY.WWW}. Kept as
 *       the raw reverse-domain identifier rather than a prettified name, since mapping GUIDs to
 *       brand names is presentation and belongs in the app.</li>
 * </ul>
 *
 * <p>Each field is bounded by the width of the column it is stored in. An EMV length prefix is
 * two digits, so a value can be up to 99 characters — comfortably inside {@code qris_merchant_name}
 * (255) and {@code acquirer} (100), but beyond {@code nmid} (50). A real NMID is 15 characters, so
 * anything longer is not one: it is dropped rather than stored truncated, which would otherwise
 * either break the insert or caption the QR with a wrong identifier.
 *
 * <p>Parsing is <b>lenient and never throws</b>: a code is accepted even when its CRC fails
 * (see {@link QrisService#validateQrisCode}), so a payload this can't read simply yields empty
 * fields rather than blocking the upload.
 */
public final class QrisCodeParser {

    private static final String QRIS_DOMESTIC_GUID = "ID.CO.QRIS.WWW";
    private static final String TAG_MERCHANT_NAME = "59";
    private static final String TAG_MERCHANT_CITY = "60";
    private static final String TAG_ADDITIONAL_DATA = "62";
    private static final String SUBTAG_TERMINAL_LABEL = "07";
    private static final String SUBTAG_GUID = "00";
    private static final String SUBTAG_NMID = "02";

    // Merchant Account Information templates, per EMVCo
    private static final int MERCHANT_TEMPLATE_FIRST = 26;
    private static final int MERCHANT_TEMPLATE_LAST = 51;

    // Storage widths — a parsed value longer than its column is not the field it claims to be
    private static final int MAX_MERCHANT_NAME = 255;
    private static final int MAX_NMID = 50;
    private static final int MAX_ACQUIRER = 100;
    private static final int MAX_TERMINAL_ID = 50;

    private QrisCodeParser() {}

    /** What the app needs to caption the QR. Any field may be null if the code doesn't carry it. */
    public record QrisIdentity(String merchantName, String nmid, String acquirer,
                               String terminalId, String merchantCity) {
        static QrisIdentity empty() {
            return new QrisIdentity(null, null, null, null, null);
        }
    }

    public static QrisIdentity parse(String qrisCode) {
        if (qrisCode == null || qrisCode.isBlank()) {
            return QrisIdentity.empty();
        }
        Map<String, String> top = readTlv(qrisCode);
        return new QrisIdentity(
                withinWidth(trimToNull(top.get(TAG_MERCHANT_NAME)), MAX_MERCHANT_NAME),
                withinWidth(findNmid(top), MAX_NMID),
                withinWidth(findAcquirer(top), MAX_ACQUIRER),
                withinWidth(findTerminalId(top), MAX_TERMINAL_ID),
                withinWidth(trimToNull(top.get(TAG_MERCHANT_CITY)), MAX_MERCHANT_NAME));
    }

    /** The NMID from whichever merchant-account template carries the QRIS domestic GUID. */
    private static String findNmid(Map<String, String> top) {
        for (int tag = MERCHANT_TEMPLATE_FIRST; tag <= MERCHANT_TEMPLATE_LAST; tag++) {
            String value = top.get(String.format("%02d", tag));
            if (value == null) {
                continue;
            }
            Map<String, String> sub = readTlv(value);
            if (QRIS_DOMESTIC_GUID.equalsIgnoreCase(trimToNull(sub.get(SUBTAG_GUID)))) {
                return trimToNull(sub.get(SUBTAG_NMID));
            }
        }
        return null;
    }

    /** The terminal label, if the code carries one — many don't, and then this is null. */
    private static String findTerminalId(Map<String, String> top) {
        String additionalData = top.get(TAG_ADDITIONAL_DATA);
        return additionalData == null ? null : trimToNull(readTlv(additionalData).get(SUBTAG_TERMINAL_LABEL));
    }

    /** The GUID of the first merchant-account template that is not the domestic QRIS switch. */
    private static String findAcquirer(Map<String, String> top) {
        for (int tag = MERCHANT_TEMPLATE_FIRST; tag <= MERCHANT_TEMPLATE_LAST; tag++) {
            String value = top.get(String.format("%02d", tag));
            if (value == null) {
                continue;
            }
            String guid = trimToNull(readTlv(value).get(SUBTAG_GUID));
            if (guid != null && !QRIS_DOMESTIC_GUID.equalsIgnoreCase(guid)) {
                return guid;
            }
        }
        return null;
    }

    /**
     * Splits {@code tag(2) length(2) value(length)} triplets. Stops at the first malformed
     * triplet instead of throwing, so a partially readable code still yields what precedes it.
     */
    private static Map<String, String> readTlv(String data) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i + 4 <= data.length()) {
            String tag = data.substring(i, i + 2);
            int length;
            try {
                length = Integer.parseInt(data.substring(i + 2, i + 4));
            } catch (NumberFormatException e) {
                break;                      // length isn't numeric — stop reading
            }
            int valueStart = i + 4;
            int valueEnd = valueStart + length;
            if (length < 0 || valueEnd > data.length()) {
                break;                      // truncated value — stop reading
            }
            out.putIfAbsent(tag, data.substring(valueStart, valueEnd));
            i = valueEnd;
        }
        return out;
    }

    /** The value, or null when it exceeds the width of the column it would be stored in. */
    private static String withinWidth(String value, int max) {
        return value != null && value.length() <= max ? value : null;
    }

    private static String trimToNull(String value) {
        return Optional.ofNullable(value).map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
    }
}
