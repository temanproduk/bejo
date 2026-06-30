package com.bejo.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "user_banks")
public class UserBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "bank_name", nullable = false)
    private String bankName = "";

    @Column(name = "bank_number", nullable = false)
    private String bankNumber = "";

    @Column(name = "bank_account_name", nullable = false)
    private String bankAccountName = "";

    @Column(name = "initial_bank_name")
    private String initialBankName;

    @Column(name = "initial_bank_number")
    private String initialBankNumber;

    @Column(name = "initial_bank_account_name")
    private String initialBankAccountName;

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getBankNumber() { return bankNumber; }
    public void setBankNumber(String bankNumber) { this.bankNumber = bankNumber; }
    public String getBankAccountName() { return bankAccountName; }
    public void setBankAccountName(String bankAccountName) { this.bankAccountName = bankAccountName; }
    public String getInitialBankName() { return initialBankName; }
    public void setInitialBankName(String initialBankName) { this.initialBankName = initialBankName; }
    public String getInitialBankNumber() { return initialBankNumber; }
    public void setInitialBankNumber(String initialBankNumber) { this.initialBankNumber = initialBankNumber; }
    public String getInitialBankAccountName() { return initialBankAccountName; }
    public void setInitialBankAccountName(String v) { this.initialBankAccountName = v; }
}
