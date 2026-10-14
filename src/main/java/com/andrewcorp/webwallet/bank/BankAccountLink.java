package com.andrewcorp.webwallet.bank;

import com.andrewcorp.webwallet.ledger.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A bank account or card and the WebWallet account its operations are imported to.
 */
@Entity
@Table(name = "bank_account_links")
public class BankAccountLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false)
    private BankConnection connection;

    @Column(nullable = false, updatable = false)
    private String externalAccountId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String currency;

    /** Null means the bank account is not synchronized. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    /** Operations up to this moment are imported. */
    @Column(nullable = false)
    private Instant syncedUntil;

    private Instant gapTo;

    private Instant gapTarget;

    private BigDecimal bankBalance;

    protected BankAccountLink() {
    }

    public BankAccountLink(BankConnection connection, String externalAccountId, String title, String currency,
                           Instant syncedUntil) {
        this.connection = connection;
        this.externalAccountId = externalAccountId;
        this.title = title;
        this.currency = currency;
        this.syncedUntil = syncedUntil;
    }

    public Long getId() {
        return id;
    }

    public BankConnection getConnection() {
        return connection;
    }

    public String getExternalAccountId() {
        return externalAccountId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCurrency() {
        return currency;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public Instant getSyncedUntil() {
        return syncedUntil;
    }

    public void setSyncedUntil(Instant syncedUntil) {
        this.syncedUntil = syncedUntil;
    }

    public Instant getGapTo() {
        return gapTo;
    }

    public Instant getGapTarget() {
        return gapTarget;
    }

    /** Operations in [gapTo, gapTarget] are imported, [syncedUntil, gapTo] is still missing. */
    public void setGap(Instant gapTo, Instant gapTarget) {
        this.gapTo = gapTo;
        this.gapTarget = gapTarget;
    }

    public BigDecimal getBankBalance() {
        return bankBalance;
    }

    public void setBankBalance(BigDecimal bankBalance) {
        this.bankBalance = bankBalance;
    }
}
