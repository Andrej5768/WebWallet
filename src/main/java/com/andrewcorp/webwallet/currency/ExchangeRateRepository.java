package com.andrewcorp.webwallet.currency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, ExchangeRate.Key> {

    /** The latest known rate on or before the date. */
    Optional<ExchangeRate> findFirstByCurrencyAndDateLessThanEqualOrderByDateDesc(String currency, LocalDate date);
}
