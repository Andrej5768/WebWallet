package com.andrewcorp.webwallet.planning;

import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * What happened to one scheduled date of a recurring payment. Dates without a row are pending; a {@code PENDING} row
 * is one the user reset, so it is no longer matched or created automatically.
 */
@Entity
@Table(name = "recurring_occurrences")
public class RecurringOccurrence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recurring_id", nullable = false, updatable = false)
    private RecurringPayment recurring;

    @Column(nullable = false, updatable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OccurrenceStatus status;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    protected RecurringOccurrence() {}

    public RecurringOccurrence(RecurringPayment recurring, LocalDate dueDate) {
        this.recurring = recurring;
        this.dueDate = dueDate;
    }

    public void paid(Transaction transaction) {
        this.status = OccurrenceStatus.PAID;
        this.transaction = transaction;
    }

    public void skipped() {
        this.status = OccurrenceStatus.SKIPPED;
        this.transaction = null;
    }

    /** Reset by the user: pending again, but no longer matched or created automatically. */
    public void pending() {
        this.status = OccurrenceStatus.PENDING;
        this.transaction = null;
    }

    public Long getId() {
        return id;
    }

    public RecurringPayment getRecurring() {
        return recurring;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public OccurrenceStatus getStatus() {
        return status;
    }

    public Transaction getTransaction() {
        return transaction;
    }
}
