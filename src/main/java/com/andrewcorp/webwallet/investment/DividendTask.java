package com.andrewcorp.webwallet.investment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/** One dividend request an hour, within the free limit of the provider (25 requests a day). */
@Component
public class DividendTask {

    private static final Logger log = LoggerFactory.getLogger(DividendTask.class);

    private final DividendService dividendService;

    public DividendTask(DividendService dividendService) {
        this.dividendService = dividendService;
    }

    @Scheduled(
            fixedDelayString = "${app.market-data.dividend-delay:1h}",
            initialDelayString = "${app.market-data.dividend-delay:1h}")
    public void update() {
        for (Long instrumentId : dividendService.due(1)) {
            try {
                dividendService.update(instrumentId);
            } catch (RestClientException | DataAccessException e) {
                log.warn(
                        "Dividends of instrument {} failed: {}",
                        instrumentId,
                        e.getClass().getSimpleName());
                dividendService.markChecked(instrumentId);
            }
        }
    }
}
