package com.bejo.constant;

/**
 * QRIS image kinds returned by {@code GET /api/qris}.
 *
 * <ul>
 *   <li>{@link #UPLOADED} — a custom image the merchant uploaded ({@code user_qris.qris_image} not null)</li>
 *   <li>{@link #MANAGED} — the generated/initial image ({@code user_qris.qris_image} null)</li>
 * </ul>
 */
public final class QrisType {

    public static final String UPLOADED = "UPLOADED";
    public static final String MANAGED = "MANAGED";

    private QrisType() {}
}
