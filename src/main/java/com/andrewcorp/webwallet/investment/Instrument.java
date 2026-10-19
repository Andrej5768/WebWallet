package com.andrewcorp.webwallet.investment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A security or other asset. A market instrument ({@link #userId} null) is shared by all users and priced by the
 * market data provider; one entered by hand belongs to its user.
 */
@Entity
@Table(name = "instruments")
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long userId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstrumentType type;

    @Column(nullable = false)
    private String currency;

    private String exchange;

    private String micCode;

    private String isin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Valuation valuation;

    private BigDecimal nominal;

    /** Annual coupon rate in percent. */
    private BigDecimal couponRate;

    /** Coupons per year. */
    private Integer couponFrequency;

    private LocalDate maturityDate;

    private BigDecimal manualPrice;

    private LocalDate manualPriceDate;

    /** Priced also without operations: a benchmark the portfolio is compared with. */
    @Column(nullable = false)
    private boolean benchmark;

    private Instant pricesCheckedAt;

    private Instant dividendsCheckedAt;

    private Instant sectorsCheckedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Instrument() {}

    public Instrument(Long userId, String symbol, InstrumentType type, String currency, Valuation valuation) {
        this.userId = userId;
        this.symbol = symbol;
        this.type = type;
        this.currency = currency;
        this.valuation = valuation;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public InstrumentType getType() {
        return type;
    }

    public void setType(InstrumentType type) {
        this.type = type;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getMicCode() {
        return micCode;
    }

    public void setMicCode(String micCode) {
        this.micCode = micCode;
    }

    public String getIsin() {
        return isin;
    }

    public void setIsin(String isin) {
        this.isin = isin;
    }

    public Valuation getValuation() {
        return valuation;
    }

    public void setValuation(Valuation valuation) {
        this.valuation = valuation;
    }

    public BigDecimal getNominal() {
        return nominal;
    }

    public void setNominal(BigDecimal nominal) {
        this.nominal = nominal;
    }

    public BigDecimal getCouponRate() {
        return couponRate;
    }

    public void setCouponRate(BigDecimal couponRate) {
        this.couponRate = couponRate;
    }

    public Integer getCouponFrequency() {
        return couponFrequency;
    }

    public void setCouponFrequency(Integer couponFrequency) {
        this.couponFrequency = couponFrequency;
    }

    public LocalDate getMaturityDate() {
        return maturityDate;
    }

    public void setMaturityDate(LocalDate maturityDate) {
        this.maturityDate = maturityDate;
    }

    public BigDecimal getManualPrice() {
        return manualPrice;
    }

    public void setManualPrice(BigDecimal manualPrice) {
        this.manualPrice = manualPrice;
    }

    public LocalDate getManualPriceDate() {
        return manualPriceDate;
    }

    public void setManualPriceDate(LocalDate manualPriceDate) {
        this.manualPriceDate = manualPriceDate;
    }

    public boolean isBenchmark() {
        return benchmark;
    }

    public void setBenchmark(boolean benchmark) {
        this.benchmark = benchmark;
    }

    public Instant getPricesCheckedAt() {
        return pricesCheckedAt;
    }

    public void setPricesCheckedAt(Instant pricesCheckedAt) {
        this.pricesCheckedAt = pricesCheckedAt;
    }

    public Instant getDividendsCheckedAt() {
        return dividendsCheckedAt;
    }

    public void setDividendsCheckedAt(Instant dividendsCheckedAt) {
        this.dividendsCheckedAt = dividendsCheckedAt;
    }

    public Instant getSectorsCheckedAt() {
        return sectorsCheckedAt;
    }

    public void setSectorsCheckedAt(Instant sectorsCheckedAt) {
        this.sectorsCheckedAt = sectorsCheckedAt;
    }
}
