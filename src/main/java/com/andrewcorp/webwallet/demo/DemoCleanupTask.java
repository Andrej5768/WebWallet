package com.andrewcorp.webwallet.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes the demo sandboxes whose day is over, with all their data. */
@Component
public class DemoCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(DemoCleanupTask.class);

    private final DemoService demo;

    public DemoCleanupTask(DemoService demo) {
        this.demo = demo;
    }

    @Scheduled(cron = "0 30 * * * *")
    public void deleteExpired() {
        int deleted = demo.deleteExpired();
        if (deleted > 0) {
            log.info("Deleted {} expired demo sandboxes", deleted);
        }
    }
}
