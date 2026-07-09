package com.bejo.util;

import java.time.ZoneId;

/**
 * The business timezone for all wall-clock dates/times in the app: WIB (UTC+7).
 * The JVM default is pinned to this at startup ({@code BejoApplication.main}), so
 * every {@code LocalDate.now()}/{@code LocalDateTime.now()} produces WIB values —
 * matching how the mobile clients send transaction timestamps. Reference this
 * constant directly where the zone must be explicit (e.g. the summary buckets).
 */
public final class AppTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

    private AppTime() {}
}
