package com.andrewcorp.webwallet.tax;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A transaction the user added to the income of the sole proprietor, or took out of it. */
@Entity
@Table(name = "tax_transaction_marks")
public class TaxTransactionMark {

    @Id
    private Long transactionId;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private boolean included;

    protected TaxTransactionMark() {}

    public TaxTransactionMark(Long transactionId, Long userId, boolean included) {
        this.transactionId = transactionId;
        this.userId = userId;
        this.included = included;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public boolean isIncluded() {
        return included;
    }

    public void setIncluded(boolean included) {
        this.included = included;
    }
}
