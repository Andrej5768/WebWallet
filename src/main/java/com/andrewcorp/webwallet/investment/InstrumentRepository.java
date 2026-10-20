package com.andrewcorp.webwallet.investment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    /** A market instrument by its symbol and exchange. */
    @Query("""
            select i from Instrument i
            where i.userId is null and i.symbol = :symbol and coalesce(i.micCode, '') = coalesce(:micCode, '')""")
    Optional<Instrument> findMarket(String symbol, String micCode);

    /** Market instruments with the symbol, on any exchange. */
    @Query("select i from Instrument i where i.userId is null and i.symbol = :symbol order by i.id")
    List<Instrument> findMarketBySymbol(String symbol);

    /** Instruments of the user: those of their operations and those they entered by hand. */
    @Query("""
            select i from Instrument i
            where i.userId = :userId
               or exists (select o from InvestmentOperation o where o.instrument = i and o.userId = :userId)
            order by i.symbol""")
    List<Instrument> findUsed(Long userId);

    /**
     * Market instruments someone holds operations of, and benchmarks, whose prices were not checked since the moment.
     */
    @Query("""
            select i from Instrument i
            where i.valuation = com.andrewcorp.webwallet.investment.Valuation.MARKET
              and (i.benchmark = true or exists (select o from InvestmentOperation o where o.instrument = i))
              and (i.pricesCheckedAt is null or i.pricesCheckedAt < :checkedBefore)
            order by i.pricesCheckedAt nulls first, i.id""")
    List<Instrument> findDueForPrices(Instant checkedBefore, Pageable pageable);

    /**
     * Market instruments in US dollars someone has operations of, whose dividends were not checked since the moment:
     * the dividend data provider knows the US listings.
     */
    @Query("""
            select i from Instrument i
            where i.valuation = com.andrewcorp.webwallet.investment.Valuation.MARKET and i.currency = 'USD'
              and i.type in (com.andrewcorp.webwallet.investment.InstrumentType.STOCK,
                             com.andrewcorp.webwallet.investment.InstrumentType.ETF,
                             com.andrewcorp.webwallet.investment.InstrumentType.FUND)
              and exists (select o from InvestmentOperation o where o.instrument = i)
              and (i.dividendsCheckedAt is null or i.dividendsCheckedAt < :checkedBefore)
            order by i.dividendsCheckedAt nulls first, i.id""")
    List<Instrument> findDueForDividends(Instant checkedBefore, Pageable pageable);

    /** Like {@link #findDueForDividends}, for the sectors: the same provider knows the US listings. */
    @Query("""
            select i from Instrument i
            where i.valuation = com.andrewcorp.webwallet.investment.Valuation.MARKET and i.currency = 'USD'
              and i.type in (com.andrewcorp.webwallet.investment.InstrumentType.STOCK,
                             com.andrewcorp.webwallet.investment.InstrumentType.ETF,
                             com.andrewcorp.webwallet.investment.InstrumentType.FUND)
              and exists (select o from InvestmentOperation o where o.instrument = i)
              and (i.sectorsCheckedAt is null or i.sectorsCheckedAt < :checkedBefore)
            order by i.sectorsCheckedAt nulls first, i.id""")
    List<Instrument> findDueForSectors(Instant checkedBefore, Pageable pageable);
}
