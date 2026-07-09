package com.bejo.entity;

import jakarta.persistence.*;

/**
 * One line of a {@link StockMutation}. Snapshots the product's {@code sku}/{@code name}/
 * {@code unitPrice} at purchase time so history is stable even if the product is later
 * changed or deleted; {@code productId} is a plain reference (no FK).
 */
@Entity
@Table(name = "stock_mutation_items")
public class StockMutationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mutation_id", nullable = false)
    private Long mutationId;

    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(name = "previous_stock", nullable = false)
    private int previousStock;

    @Column(name = "new_stock", nullable = false)
    private int newStock;

    @Column(name = "quantity_change", nullable = false)
    private int quantityChange;

    // Priced only for PURCHASE mutations; null for ADJUSTMENT / UPDATE
    @Column(name = "unit_price")
    private Long unitPrice;

    private Long subtotal;

    public Long getId() { return id; }
    public Long getMutationId() { return mutationId; }
    public void setMutationId(Long mutationId) { this.mutationId = mutationId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getPreviousStock() { return previousStock; }
    public void setPreviousStock(int previousStock) { this.previousStock = previousStock; }
    public int getNewStock() { return newStock; }
    public void setNewStock(int newStock) { this.newStock = newStock; }
    public int getQuantityChange() { return quantityChange; }
    public void setQuantityChange(int quantityChange) { this.quantityChange = quantityChange; }
    public Long getUnitPrice() { return unitPrice; }
    public void setUnitPrice(Long unitPrice) { this.unitPrice = unitPrice; }
    public Long getSubtotal() { return subtotal; }
    public void setSubtotal(Long subtotal) { this.subtotal = subtotal; }
}
