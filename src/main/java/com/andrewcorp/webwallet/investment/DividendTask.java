package com.andrewcorp.webwallet.investment;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * One request an hour to the fundamental data provider, within its free limit (25 requests a day): the dividends of an
 * instrument when some are due, else its sectors.
 */
@Component
public class DividendTask {

    private static final Logger log = LoggerFactory.getLogger(DividendTask.class);

    private final DividendService dividendService;
    private final SectorService sectorService;

    public DividendTask(DividendService dividendService, SectorService sectorService) {
        this.dividendService = dividendService;
        this.sectorService = sectorService;
    }

    @Scheduled(
            fixedDelayString = "${app.market-data.dividend-delay:1h}",
            initialDelayString = "${app.market-data.dividend-delay:1h}")
    public void update() {
        List<Long> dividends = dividendService.due(1);
        if (dividends.isEmpty()) {
            updateSectors();
            return;
        }
        for (Long instrumentId : dividends) {
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

    private void updateSectors() {
        for (Long instrumentId : sectorService.due(1)) {
            try {
                sectorService.update(instrumentId);
            } catch (RestClientException | DataAccessException e) {
                log.warn(
                        "Sectors of instrument {} failed: {}",
                        instrumentId,
                        e.getClass().getSimpleName());
                sectorService.markChecked(instrumentId);
            }
        }
    }
}
