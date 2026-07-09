package com.bejo.constant;

/**
 * Canonical set of {@code purchases.type} values — how a stock mutation was produced.
 *
 * <ul>
 *   <li>{@link #PURCHASE} — restock via {@code POST /api/stock-mutations} (stock in, priced)</li>
 *   <li>{@link #ADJUSTMENT} — stock opname via {@code POST /api/products/stock-adjustments}</li>
 *   <li>{@link #UPDATE} — a {@code PATCH /api/products/{id}} that changed the stock</li>
 * </ul>
 */
public final class StockMutationType {

    public static final String PURCHASE = "PURCHASE";
    public static final String ADJUSTMENT = "ADJUSTMENT";
    public static final String UPDATE = "UPDATE";

    private StockMutationType() {}
}
