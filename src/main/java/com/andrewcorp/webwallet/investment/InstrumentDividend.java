package com.andrewcorp.webwallet.investment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A dividend per share of a market instrument: paid, or declared and still to be paid. */
@Entity
@Table(name = "instrument_dividends")
public class InstrumentDividend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long instrumentId;

    /** Who holds the instrument before this day gets the dividend. */
    @Column(nullable = false, updatable = false)
    private LocalDate exDate;

    private LocalDate paymentDate;

    private LocalDate declarationDate;

    private LocalDate recordDate;

    /** Per share, in the currency of the instrument. */
    @Column(nullable = false)
    private BigDecimal amount;

    protected InstrumentDividend() {}

    public InstrumentDividend(Long instrumentId, LocalDate exDate) {
        this.instrumentId = instrumentId;
        this.exDate = exDate;
    }

    /** The day the money comes: the payment date, else the ex-dividend date. */
    public LocalDate payDay() {
        return paymentDate != null ? paymentDate : exDate;
    }

    public Long getId() {
        return id;
    }

    public Long getInstrumentId() {
        return instrumentId;
    }

    public LocalDate getExDate() {
        return exDate;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDate paymentDate) {
        this.paymentDate = paymentDate;
    }

    public LocalDate getDeclarationDate() {
        return declarationDate;
    }

    public void setDeclarationDate(LocalDate declarationDate) {
        this.declarationDate = declarationDate;
    }

    public LocalDate getRecordDate() {
        return recordDate;
    }

    public void setRecordDate(LocalDate recordDate) {
        this.recordDate = recordDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
