package com.andrewcorp.webwallet.currency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Loads the NBU rates of the day. The NBU publishes them in the afternoon of the previous working day.
 */
@Component
public class ExchangeRateTask {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateTask.class);

    private final ExchangeRateService exchangeRates;

    public ExchangeRateTask(ExchangeRateService exchangeRates) {
        this.exchangeRates = exchangeRates;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Europe/Kyiv")
    public void loadToday() {
        try {
            log.info("Loaded {} NBU exchange rates", exchangeRates.loadToday());
        } catch (RestClientException e) {
            log.warn("Failed to load NBU exchange rates", e);
        }
    }
}
