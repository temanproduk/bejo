package com.bejo.commerce.domain;

import jakarta.persistence.*;

/**
 * One line of a sale — a product sold as part of a {@link Transaction} (MANUAL/PARSED
 * entry with items). Snapshots the product's {@code sku}/{@code name}/{@code unitPrice}
 * at sale time so history is stable even if the product is later edited or deleted;
 * {@code productId} is a plain reference (no FK). {@code historyId} references
 * {@code bejo_history.id} (the parent transaction row).
 */
@Entity
@Table(name = "transaction_items")
public class TransactionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "history_id", nullable = false)
    private Long historyId;

    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int quantity;

    // Selling price per unit at sale time, IDR (snapshot of the product's price)
    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    // unit_price * quantity, IDR
    @Column(nullable = false)
    private long subtotal;

    public Long getId() { return id; }
    public Long getHistoryId() { return historyId; }
    public void setHistoryId(Long historyId) { this.historyId = historyId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public long getUnitPrice() { return unitPrice; }
    public void setUnitPrice(long unitPrice) { this.unitPrice = unitPrice; }
    public long getSubtotal() { return subtotal; }
    public void setSubtotal(long subtotal) { this.subtotal = subtotal; }
}
