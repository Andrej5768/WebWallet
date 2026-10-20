package com.andrewcorp.webwallet.tax;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;

/** The user's own tax settings of a year; a null value takes the default of the year. */
@Entity
@Table(name = "tax_settings")
@IdClass(TaxSettings.Key.class)
public class TaxSettings {

    @Id
    private Long userId;

    @Id
    private Integer year;

    private Short fopGroup;
    private Boolean fopVat;
    private BigDecimal singleTaxRate;
    private BigDecimal fopMilitaryRate;
    private BigDecimal singleTaxMonthly;
    private BigDecimal fopMilitaryMonthly;
    private Boolean esvEnabled;
    private BigDecimal esvRate;
    private BigDecimal minWage;
    private BigDecimal pdfoRate;
    private BigDecimal investmentMilitaryRate;
    private Boolean foreignDocumentDefault;

    protected TaxSettings() {}

    public TaxSettings(Long userId, int year) {
        this.userId = userId;
        this.year = year;
    }

    /** The settings that apply: these values over the defaults of the year. */
    public TaxRates rates() {
        int group = fopGroup != null ? fopGroup : 3;
        boolean vat = Boolean.TRUE.equals(fopVat);
        TaxRates defaults = TaxDefaults.of(year, group, vat);
        return new TaxRates(
                group,
                vat,
                or(singleTaxRate, defaults.singleTaxRate()),
                or(fopMilitaryRate, defaults.fopMilitaryRate()),
                or(singleTaxMonthly, defaults.singleTaxMonthly()),
                or(fopMilitaryMonthly, defaults.fopMilitaryMonthly()),
                esvEnabled != null ? esvEnabled : defaults.esvEnabled(),
                or(esvRate, defaults.esvRate()),
                or(minWage, defaults.minWage()),
                or(pdfoRate, defaults.pdfoRate()),
                or(investmentMilitaryRate, defaults.investmentMilitaryRate()),
                foreignDocumentDefault != null ? foreignDocumentDefault : defaults.foreignDocumentDefault());
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    public Short getFopGroup() {
        return fopGroup;
    }

    public void setFopGroup(Short fopGroup) {
        this.fopGroup = fopGroup;
    }

    public Boolean getFopVat() {
        return fopVat;
    }

    public void setFopVat(Boolean fopVat) {
        this.fopVat = fopVat;
    }

    public BigDecimal getSingleTaxRate() {
        return singleTaxRate;
    }

    public void setSingleTaxRate(BigDecimal singleTaxRate) {
        this.singleTaxRate = singleTaxRate;
    }

    public BigDecimal getFopMilitaryRate() {
        return fopMilitaryRate;
    }

    public void setFopMilitaryRate(BigDecimal fopMilitaryRate) {
        this.fopMilitaryRate = fopMilitaryRate;
    }

    public BigDecimal getSingleTaxMonthly() {
        return singleTaxMonthly;
    }

    public void setSingleTaxMonthly(BigDecimal singleTaxMonthly) {
        this.singleTaxMonthly = singleTaxMonthly;
    }

    public BigDecimal getFopMilitaryMonthly() {
        return fopMilitaryMonthly;
    }

    public void setFopMilitaryMonthly(BigDecimal fopMilitaryMonthly) {
        this.fopMilitaryMonthly = fopMilitaryMonthly;
    }

    public Boolean getEsvEnabled() {
        return esvEnabled;
    }

    public void setEsvEnabled(Boolean esvEnabled) {
        this.esvEnabled = esvEnabled;
    }

    public BigDecimal getEsvRate() {
        return esvRate;
    }

    public void setEsvRate(BigDecimal esvRate) {
        this.esvRate = esvRate;
    }

    public BigDecimal getMinWage() {
        return minWage;
    }

    public void setMinWage(BigDecimal minWage) {
        this.minWage = minWage;
    }

    public BigDecimal getPdfoRate() {
        return pdfoRate;
    }

    public void setPdfoRate(BigDecimal pdfoRate) {
        this.pdfoRate = pdfoRate;
    }

    public BigDecimal getInvestmentMilitaryRate() {
        return investmentMilitaryRate;
    }

    public void setInvestmentMilitaryRate(BigDecimal investmentMilitaryRate) {
        this.investmentMilitaryRate = investmentMilitaryRate;
    }

    public Boolean getForeignDocumentDefault() {
        return foreignDocumentDefault;
    }

    public void setForeignDocumentDefault(Boolean foreignDocumentDefault) {
        this.foreignDocumentDefault = foreignDocumentDefault;
    }

    public record Key(Long userId, Integer year) implements Serializable {}
}
