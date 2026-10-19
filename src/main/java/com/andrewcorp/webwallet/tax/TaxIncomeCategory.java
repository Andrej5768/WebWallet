package com.andrewcorp.webwallet.tax;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

/** An income category whose income is income of the sole proprietor on any account. */
@Entity
@Table(name = "tax_income_categories")
@IdClass(TaxIncomeCategory.Key.class)
public class TaxIncomeCategory {

    @Id
    private Long userId;

    @Id
    private Long categoryId;

    protected TaxIncomeCategory() {}

    public TaxIncomeCategory(Long userId, Long categoryId) {
        this.userId = userId;
        this.categoryId = categoryId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public record Key(Long userId, Long categoryId) implements Serializable {}
}
