package com.bejo.commerce.domain;

import com.bejo.commerce.domain.StockMutationType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A purchase order (restock) — raises the stock of one or more products at once.
 * Scoped per merchant ({@code username}). Line items live in {@link StockMutationItem}.
 */
@Entity
@Table(name = "stock_mutations", schema = "commerce")
public class StockMutation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    // PURCHASE | ADJUSTMENT | UPDATE — how this stock mutation was produced
    @Column(nullable = false)
    private String type = StockMutationType.PURCHASE;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
