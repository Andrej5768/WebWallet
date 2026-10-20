package com.andrewcorp.webwallet.investment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;

/** The weight of a sector in a market instrument: 1 for a company, a part of a fund. */
@Entity
@Table(name = "instrument_sectors")
@IdClass(InstrumentSector.Key.class)
public class InstrumentSector {

    @Id
    private Long instrumentId;

    @Id
    @Enumerated(EnumType.STRING)
    private Sector sector;

    @Column(nullable = false)
    private BigDecimal weight;

    protected InstrumentSector() {}

    public InstrumentSector(Long instrumentId, Sector sector, BigDecimal weight) {
        this.instrumentId = instrumentId;
        this.sector = sector;
        this.weight = weight;
    }

    public Long getInstrumentId() {
        return instrumentId;
    }

    public Sector getSector() {
        return sector;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public record Key(Long instrumentId, Sector sector) implements Serializable {}
}
