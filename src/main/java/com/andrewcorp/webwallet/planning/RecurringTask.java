package com.andrewcorp.webwallet.planning;

import com.andrewcorp.webwallet.common.error.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Matches due occurrences of recurring payments with the transactions imported meanwhile and creates the automatic
 * ones, so that they are paid also when nobody looks at them.
 */
@Component
public class RecurringTask {

    private static final Logger log = LoggerFactory.getLogger(RecurringTask.class);

    private final RecurringService recurringService;
    private final RecurringPaymentRepository recurringPayments;
    private final Clock clock;

    public RecurringTask(RecurringService recurringService, RecurringPaymentRepository recurringPayments, Clock clock) {
        this.recurringService = recurringService;
        this.recurringPayments = recurringPayments;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.recurring.cron:0 5 * * * *}", zone = "Europe/Kyiv")
    public void refresh() {
        LocalDate since = LocalDate.now(clock).minusDays(RecurringService.LOOKBACK_DAYS);
        for (Long userId : recurringPayments.findUsersWithActive(since)) {
            try {
                recurringService.refresh(userId);
            } catch (ApiException e) {
                // E.g. the category of an automatic payment became one of another kind
                log.warn("Recurring payments of user {} failed: {}", userId, e.code());
            } catch (DataAccessException e) {
                log.error(
                        "Recurring payments of user {} failed: {}",
                        userId,
                        e.getMostSpecificCause().getClass().getSimpleName());
            }
        }
    }
}
