package com.andrewcorp.webwallet.investment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InstrumentPriceRepository extends JpaRepository<InstrumentPrice, InstrumentPrice.Key> {

    List<InstrumentPrice> findByInstrumentIdAndDateBetweenOrderByDate(Long instrumentId, LocalDate from, LocalDate to);

    /** The two latest closes on or before the date of each instrument, latest first. */
    @Query(value = """
            select p.* from (
                select p.*, row_number() over (partition by p.instrument_id order by p.date desc) as n
                from instrument_prices p
                where p.instrument_id in (:instrumentIds) and p.date <= :date) p
            where p.n <= 2
            order by p.instrument_id, p.date desc""", nativeQuery = true)
    List<InstrumentPrice> findLatest(Collection<Long> instrumentIds, LocalDate date);

    @Query("select max(p.date) from InstrumentPrice p where p.instrumentId = :instrumentId")
    Optional<LocalDate> findLastDate(Long instrumentId);

    @Query("select min(p.date) from InstrumentPrice p where p.instrumentId = :instrumentId")
    Optional<LocalDate> findFirstDate(Long instrumentId);

    List<InstrumentPrice> findByInstrumentIdInAndDateBetween(
            Collection<Long> instrumentIds, LocalDate from, LocalDate to);
}
