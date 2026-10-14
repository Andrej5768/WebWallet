package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes one Monobank request per connection whenever the rate limit allows it, until all accounts are up to date.
 */
@Component
public class MonobankSyncTask {

    private static final Logger log = LoggerFactory.getLogger(MonobankSyncTask.class);

    private final MonobankService monobank;

    public MonobankSyncTask(MonobankService monobank) {
        this.monobank = monobank;
    }

    @Scheduled(
            fixedDelayString = "${app.monobank.sync-delay:15s}",
            initialDelayString = "${app.monobank.sync-delay:15s}")
    public void sync() {
        for (Long connectionId : monobank.connectionsReadyForRequest()) {
            try {
                monobank.syncStep(connectionId);
            } catch (ApiException e) {
                log.warn("Monobank sync of connection {} failed: {}", connectionId, e.code());
                monobank.recordError(connectionId, e.code().name());
            } catch (DataAccessException e) {
                // The message would contain the values of the operation; the type is enough to investigate
                log.error(
                        "Monobank sync of connection {} failed: {}",
                        connectionId,
                        e.getMostSpecificCause().getClass().getSimpleName());
                monobank.recordError(connectionId, ErrorCode.INTERNAL_ERROR.name());
            } catch (RuntimeException e) {
                log.error("Monobank sync of connection {} failed", connectionId, e);
                monobank.recordError(connectionId, ErrorCode.INTERNAL_ERROR.name());
            }
        }
    }
}
