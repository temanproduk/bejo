package com.bejo.commerce.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions", schema = "commerce")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", unique = true, nullable = false)
    private String transactionId;

    @Column(name = "transaction_date", nullable = false)
    private LocalDateTime transactionDate;

    @Column(nullable = false)
    private Long amount;

    private String status;

    @Column(name = "invoice_id")
    private String invoiceId;

    @Column(name = "settlement_date")
    private LocalDateTime settlementDate;

    // When an awaiting-payment transaction was paid (null for one that never waited, and while it waits)
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    // Idempotency-Key of the PARSED payment that settled this transaction, so a retry of that request
    // is recognised (the payment is merged into this row and has no row of its own)
    @Column(name = "payment_reference")
    private String paymentReference;

    private String source;

    @Column(name = "customer_name")
    private String customerName;

    private String rrn;

    @Column(name = "merchant_pan")
    private String merchantPan;

    @Column(name = "customer_pan")
    private String customerPan;

    // General-purpose extra context (e.g. the raw QRIS callback payload JSON). The QRIS
    // provider calls this "additionalInfo" on the wire; we store it under the shorter "info".
    @Column(columnDefinition = "TEXT")
    private String info;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "scrape_date")
    private LocalDateTime scrapeDate;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String acquirer;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    // Audit: the username of the account/sub-account that created this transaction, and the one
    // that last edited it (MANUAL/PARSED only; both NULL on a server-recorded MANAGED callback row).
    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    // Soft delete: NULL = active, non-NULL = when the user deleted it
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Long getId() { return id; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public LocalDateTime getTransactionDate() { return transactionDate; }
    public void setTransactionDate(LocalDateTime transactionDate) { this.transactionDate = transactionDate; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public LocalDateTime getSettlementDate() { return settlementDate; }
    public void setSettlementDate(LocalDateTime settlementDate) { this.settlementDate = settlementDate; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getRrn() { return rrn; }
    public void setRrn(String rrn) { this.rrn = rrn; }
    public String getMerchantPan() { return merchantPan; }
    public void setMerchantPan(String merchantPan) { this.merchantPan = merchantPan; }
    public String getCustomerPan() { return customerPan; }
    public void setCustomerPan(String customerPan) { this.customerPan = customerPan; }
    public String getInfo() { return info; }
    public void setInfo(String info) { this.info = info; }
    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }
    public LocalDateTime getScrapeDate() { return scrapeDate; }
    public void setScrapeDate(LocalDateTime scrapeDate) { this.scrapeDate = scrapeDate; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getAcquirer() { return acquirer; }
    public void setAcquirer(String acquirer) { this.acquirer = acquirer; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
