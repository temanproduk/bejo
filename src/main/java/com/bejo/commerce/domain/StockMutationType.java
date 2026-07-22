package com.bejo.commerce.domain;

/**
 * Canonical set of {@code purchases.type} values — how a stock mutation was produced.
 *
 * <ul>
 *   <li>{@link #PURCHASE} — restock via {@code POST /api/stock-mutations} (stock in, priced)</li>
 *   <li>{@link #ADJUSTMENT} — stock opname via {@code POST /api/stock-mutations/adjustments}</li>
 *   <li>{@link #UPDATE} — a {@code PATCH /api/products/{id}} that changed the stock</li>
 *   <li>{@link #SALE} — a {@code POST /api/transactions} with items (stock out, sold)</li>
 * </ul>
 */
public final class StockMutationType {

    public static final String PURCHASE = "PURCHASE";
    public static final String ADJUSTMENT = "ADJUSTMENT";
    public static final String UPDATE = "UPDATE";
    public static final String SALE = "SALE";

    private StockMutationType() {}
}
