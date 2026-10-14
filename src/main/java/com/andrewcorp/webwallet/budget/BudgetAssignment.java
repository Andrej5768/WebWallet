package com.andrewcorp.webwallet.budget;

import com.andrewcorp.webwallet.ledger.category.Category;
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
import java.time.LocalDate;

/**
 * Money put into the envelope of an expense category for a month, in the base currency of the user.
 */
@Entity
@Table(name = "budget_assignments")
public class BudgetAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    /** First day of the month. */
    @Column(nullable = false, updatable = false)
    private LocalDate month;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false, updatable = false)
    private Category category;

    @Column(nullable = false)
    private BigDecimal amount;

    protected BudgetAssignment() {}

    public BudgetAssignment(Long userId, LocalDate month, Category category, BigDecimal amount) {
        this.userId = userId;
        this.month = month;
        this.category = category;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getMonth() {
        return month;
    }

    public Category getCategory() {
        return category;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
