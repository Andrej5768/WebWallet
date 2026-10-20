package com.andrewcorp.webwallet.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes the accounts whose deletion grace period is over, with all their data. */
@Component
public class AccountDeletionTask {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionTask.class);

    private final AccountDeletionService deletion;

    public AccountDeletionTask(AccountDeletionService deletion) {
        this.deletion = deletion;
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void deleteDue() {
        int deleted = deletion.deleteDue();
        if (deleted > 0) {
            log.info("Deleted {} accounts at the end of their deletion grace period", deleted);
        }
    }
}
