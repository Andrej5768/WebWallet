package com.andrewcorp.webwallet.ledger.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    /** ISO 4217 code; cannot change once the account exists. */
    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false)
    private boolean inBudget;

    @Column(nullable = false)
    private boolean archived;

    @Column(nullable = false)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    private BigDecimal creditLimit;

    /** Annual interest rate in percent, for loans, deposits and credit cards. */
    private BigDecimal interestRate;

    @Column(nullable = false)
    private int sortOrder;

    /** System the account was imported from, e.g. {@code ZENMONEY}. */
    private String externalSource;

    private String externalId;

    /** Last 4 digits of the bank cards of the account. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> cardNumbers;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    public Account(Long userId, String name, AccountType type, String currency) {
        this.userId = userId;
        this.name = name;
        this.type = type;
        this.currency = currency;
        this.inBudget = type.inBudgetByDefault();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AccountType getType() {
        return type;
    }

    public void setType(AccountType type) {
        this.type = type;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isInBudget() {
        return inBudget;
    }

    public void setInBudget(boolean inBudget) {
        this.inBudget = inBudget;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public void setOpeningBalance(BigDecimal openingBalance) {
        this.openingBalance = openingBalance;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getExternalSource() {
        return externalSource;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternal(String source, String id) {
        this.externalSource = source;
        this.externalId = id;
    }

    public List<String> getCardNumbers() {
        return cardNumbers;
    }

    public void setCardNumbers(List<String> cardNumbers) {
        this.cardNumbers = cardNumbers;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
