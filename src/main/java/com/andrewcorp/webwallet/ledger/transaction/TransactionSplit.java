package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.ledger.category.Category;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Part of an expense or income assigned to one category.
 */
@Entity
@Table(name = "transaction_splits")
public class TransactionSplit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    /** Null means uncategorized. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    private BigDecimal amount;

    private String comment;

    protected TransactionSplit() {}

    TransactionSplit(Transaction transaction, Category category, BigDecimal amount, String comment) {
        this.transaction = transaction;
        this.category = category;
        this.amount = amount;
        this.comment = comment;
    }

    public Long getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getComment() {
        return comment;
    }
}
