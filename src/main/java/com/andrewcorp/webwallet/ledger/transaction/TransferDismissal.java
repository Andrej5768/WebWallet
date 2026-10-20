package com.andrewcorp.webwallet.ledger.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** An expense and an income the user said are not one transfer; the pair is not suggested again. */
@Entity
@Table(name = "transfer_dismissals")
public class TransferDismissal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long expenseId;

    @Column(nullable = false)
    private Long incomeId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected TransferDismissal() {}

    public TransferDismissal(Long userId, Long expenseId, Long incomeId) {
        this.userId = userId;
        this.expenseId = expenseId;
        this.incomeId = incomeId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getExpenseId() {
        return expenseId;
    }

    public Long getIncomeId() {
        return incomeId;
    }
}
