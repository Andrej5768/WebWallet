package com.andrewcorp.webwallet.currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, ExchangeRate.Key> {

    /** Plain values rather than entities: thousands of them would be dirty-checked on every flush. */
    @Query("select r.date as date, r.rate as rate from ExchangeRate r where r.currency = :currency")
    List<RateRow> findRatesOf(String currency);

    interface RateRow {
        LocalDate getDate();

        BigDecimal getRate();
    }

    /** Concurrent loads of the same rates do not fail on the primary key. */
    @Modifying
    @Query(
            value = "insert into exchange_rates (currency, date, rate) values (:currency, :date, :rate)"
                    + " on conflict do nothing",
            nativeQuery = true)
    void insertIfAbsent(String currency, LocalDate date, BigDecimal rate);
}
