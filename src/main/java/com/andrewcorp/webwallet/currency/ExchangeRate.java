package com.andrewcorp.webwallet.currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Official NBU rate: how many UAH one unit of the currency costs on the date.
 */
@Entity
@Table(name = "exchange_rates")
@IdClass(ExchangeRate.Key.class)
public class ExchangeRate {

    @Id
    private String currency;

    @Id
    private LocalDate date;

    @Column(nullable = false)
    private BigDecimal rate;

    protected ExchangeRate() {
    }

    public ExchangeRate(String currency, LocalDate date, BigDecimal rate) {
        this.currency = currency;
        this.date = date;
        this.rate = rate;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getDate() {
        return date;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public static class Key implements Serializable {

        private String currency;
        private LocalDate date;

        protected Key() {
        }

        public Key(String currency, LocalDate date) {
            this.currency = currency;
            this.date = date;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && Objects.equals(currency, other.currency) && Objects.equals(date, other.date);
        }

        @Override
        public int hashCode() {
            return Objects.hash(currency, date);
        }
    }
}
