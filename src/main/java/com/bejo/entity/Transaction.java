package com.bejo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cuan_history")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", unique = true, nullable = false)
    private String transactionId;

    @Column(name = "transaction_date")
    private LocalDateTime transactionDate;

    private Long amount;

    private String status;

    @Column(name = "invoice_id")
    private String invoiceId;

    @Column(name = "settlement_date")
    private LocalDateTime settlementDate;

    private String source;

    @Column(name = "customer_name")
    private String customerName;

    private String rrn;

    @Column(name = "merchant_pan")
    private String merchantPan;

    @Column(name = "customer_pan")
    private String customerPan;

    @Column(columnDefinition = "TEXT")
    private String info;

    private String username;

    @Column(name = "scrape_date")
    private LocalDateTime scrapeDate;

    private String type;

    private String acquirer;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    public Long getId() { return id; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public LocalDateTime getTransactionDate() { return transactionDate; }
    public void setTransactionDate(LocalDateTime transactionDate) { this.transactionDate = transactionDate; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
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
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public LocalDateTime getScrapeDate() { return scrapeDate; }
    public void setScrapeDate(LocalDateTime scrapeDate) { this.scrapeDate = scrapeDate; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getAcquirer() { return acquirer; }
    public void setAcquirer(String acquirer) { this.acquirer = acquirer; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
