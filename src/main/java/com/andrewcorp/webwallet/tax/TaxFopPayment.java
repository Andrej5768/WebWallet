package com.andrewcorp.webwallet.tax;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

/** A quarter whose taxes the user marked as paid. */
@Entity
@Table(name = "tax_fop_payments")
@IdClass(TaxFopPayment.Key.class)
public class TaxFopPayment {

    @Id
    private Long userId;

    @Id
    private Integer year;

    @Id
    private Short quarter;

    protected TaxFopPayment() {}

    public TaxFopPayment(Long userId, int year, int quarter) {
        this.userId = userId;
        this.year = year;
        this.quarter = (short) quarter;
    }

    public int getQuarter() {
        return quarter;
    }

    public record Key(Long userId, Integer year, Short quarter) implements Serializable {}
}
