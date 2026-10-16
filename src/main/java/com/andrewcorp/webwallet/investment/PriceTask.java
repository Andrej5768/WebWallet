package com.andrewcorp.webwallet.investment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Every minute updates the prices of a few instruments that are due, within the limit of the free plan of the
 * provider (8 requests a minute).
 */
@Component
public class PriceTask {

    private static final Logger log = LoggerFactory.getLogger(PriceTask.class);

    /** Instruments per tick; below the per-minute limit of the provider. */
    static final int PER_TICK = 6;

    private final PriceService priceService;

    public PriceTask(PriceService priceService) {
        this.priceService = priceService;
    }

    @Scheduled(
            fixedDelayString = "${app.market-data.price-delay:1m}",
            initialDelayString = "${app.market-data.price-delay:1m}")
    public void update() {
        for (Long instrumentId : priceService.due(PER_TICK)) {
            try {
                priceService.update(instrumentId);
            } catch (RestClientException | DataAccessException e) {
                log.warn(
                        "Prices of instrument {} failed: {}",
                        instrumentId,
                        e.getClass().getSimpleName());
                priceService.markChecked(instrumentId);
            }
        }
    }
}
