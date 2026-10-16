package com.andrewcorp.webwallet.currency;

import com.andrewcorp.webwallet.user.UserService;
import org.springframework.stereotype.Component;

/** Converters to the base currency of a user, or to a currency they asked for. */
@Component
public class Converters {

    private final ExchangeRateService rates;
    private final UserService users;

    public Converters(ExchangeRateService rates, UserService users) {
        this.rates = rates;
        this.users = users;
    }

    /** To the base currency of the user. */
    public CurrencyConverter base(Long userId) {
        return rates.converterTo(users.get(userId).getBaseCurrency());
    }

    /** To the currency, or the base currency of the user when none is given. */
    public CurrencyConverter to(Long userId, String currency) {
        return currency != null ? rates.converterTo(currency) : base(userId);
    }

    /** To the currency. */
    public CurrencyConverter of(String currency) {
        return rates.converterTo(currency);
    }

    /** Converters to several currencies, created once each. */
    public ConverterCache cache() {
        return new ConverterCache(rates);
    }
}
