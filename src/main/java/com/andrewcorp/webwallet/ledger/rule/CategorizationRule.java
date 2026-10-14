package com.andrewcorp.webwallet.ledger.rule;

import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

/**
 * Puts an imported operation into a category when every given condition matches.
 */
@Entity
@Table(name = "categorization_rules")
public class CategorizationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payee_id")
    private Payee payee;

    /** Case-insensitive part of the payee name. */
    private String payeePattern;

    private Short mcc;

    private String bankCategory;

    /** Created automatically from a manual change of a category. */
    @Column(nullable = false)
    private boolean learned;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected CategorizationRule() {}

    public CategorizationRule(Long userId, Category category) {
        this.userId = userId;
        this.category = category;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Whether every condition of the rule holds for the operation. */
    public boolean matches(Payee operationPayee, Short operationMcc, String operationBankCategory) {
        if (payee != null && (operationPayee == null || !payee.getId().equals(operationPayee.getId()))) {
            return false;
        }
        if (payeePattern != null
                && (operationPayee == null
                        || !operationPayee
                                .getName()
                                .toLowerCase(Locale.ROOT)
                                .contains(payeePattern.toLowerCase(Locale.ROOT)))) {
            return false;
        }
        if (mcc != null && !mcc.equals(operationMcc)) {
            return false;
        }
        return bankCategory == null || bankCategory.equalsIgnoreCase(operationBankCategory);
    }

    /** More specific rules win: a payee beats a name pattern, which beats an MCC, which beats a bank category. */
    public int specificity() {
        return (payee != null ? 8 : 0)
                + (payeePattern != null ? 4 : 0)
                + (mcc != null ? 2 : 0)
                + (bankCategory != null ? 1 : 0);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public Payee getPayee() {
        return payee;
    }

    public void setPayee(Payee payee) {
        this.payee = payee;
    }

    public String getPayeePattern() {
        return payeePattern;
    }

    public void setPayeePattern(String payeePattern) {
        this.payeePattern = payeePattern;
    }

    public Short getMcc() {
        return mcc;
    }

    public void setMcc(Short mcc) {
        this.mcc = mcc;
    }

    public String getBankCategory() {
        return bankCategory;
    }

    public void setBankCategory(String bankCategory) {
        this.bankCategory = bankCategory;
    }

    public boolean isLearned() {
        return learned;
    }

    public void setLearned(boolean learned) {
        this.learned = learned;
    }
}
