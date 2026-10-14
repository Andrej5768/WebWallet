package com.andrewcorp.webwallet.currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, ExchangeRate.Key> {

    List<ExchangeRate> findByCurrency(String currency);

    /** Concurrent loads of the same rates do not fail on the primary key. */
    @Modifying
    @Query(
            value = "insert into exchange_rates (currency, date, rate) values (:currency, :date, :rate)"
                    + " on conflict do nothing",
            nativeQuery = true)
    void insertIfAbsent(String currency, LocalDate date, BigDecimal rate);
}
