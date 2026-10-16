package com.andrewcorp.webwallet.investment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** The closing price of an instrument on a trading day, in its currency. */
@Entity
@Table(name = "instrument_prices")
@IdClass(InstrumentPrice.Key.class)
public class InstrumentPrice {

    @Id
    @Column(name = "instrument_id")
    private Long instrumentId;

    @Id
    private LocalDate date;

    @Column(nullable = false)
    private BigDecimal close;

    protected InstrumentPrice() {}

    public InstrumentPrice(Long instrumentId, LocalDate date, BigDecimal close) {
        this.instrumentId = instrumentId;
        this.date = date;
        this.close = close;
    }

    public Long getInstrumentId() {
        return instrumentId;
    }

    public LocalDate getDate() {
        return date;
    }

    public BigDecimal getClose() {
        return close;
    }

    public void setClose(BigDecimal close) {
        this.close = close;
    }

    public record Key(Long instrumentId, LocalDate date) implements Serializable {

        public Key() {
            this(null, null);
        }
    }
}
