package com.andrewcorp.webwallet.currency;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Converters to several target currencies within one request, created once per currency. */
public final class ConverterCache {

    private final ExchangeRateService rates;
    private final Map<String, CurrencyConverter> converters = new HashMap<>();

    ConverterCache(ExchangeRateService rates) {
        this.rates = rates;
    }

    public CurrencyConverter to(String currency) {
        return converters.computeIfAbsent(currency, rates::converterTo);
    }

    /** Currencies left out by any of the converters, sorted. */
    public List<String> unconverted() {
        TreeSet<String> currencies = new TreeSet<>();
        converters.values().forEach(converter -> currencies.addAll(converter.unconverted()));
        return List.copyOf(currencies);
    }
}
